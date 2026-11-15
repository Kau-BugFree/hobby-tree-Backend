#!/usr/bin/env python3
"""Preinstalled main release executor. No remote shell, arbitrary paths, or self-update."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
import zipfile
import zlib

ROOT = Path('/var/lib/hobbytree/deployments')
STATE = ROOT / 'main-release'
CONFIG = Path('/etc/hobbytree/main-release.json')
REGISTRY = '494915081306.dkr.ecr.ap-northeast-2.amazonaws.com/hobbytree/backend'
REGION = 'ap-northeast-2'
SHA = r'[0-9a-f]{64}'
DIGEST = 'sha256:' + SHA


def require(ok, message='Release invariant failed'):
    if not ok:
        raise ValueError(message)


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def protected(path):
    path = Path(path)
    for entry in [path, *path.parents]:
        info = entry.lstat()
        require(not stat.S_ISLNK(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022,
                'Untrusted installation path')
    return path


def save(path, value):
    protected(path.parent)
    raw = canonical(value)
    fd, name = tempfile.mkstemp(dir=path.parent, prefix='.release-')
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def payload(value, now=None):
    require(set(value) == {'format', 'revision', 'digest', 'image_id', 'sql_set_sha256',
                           'target', 'request', 'expires_at'})
    require(type(value['format']) is int and value['format'] == 1)
    require(re.fullmatch(r'[0-9a-f]{40}', value['revision']))
    require(all(re.fullmatch(DIGEST, value[k]) for k in ('digest', 'image_id')))
    require(re.fullmatch(SHA, value['sql_set_sha256']))
    require(type(value['target']) is int and 24 <= value['target'] <= 1000)
    require(re.fullmatch(r'[1-9][0-9]{0,19}-[1-9][0-9]{0,4}', value['request']))
    now = time.time() if now is None else now
    require(type(value['expires_at']) is int and now < value['expires_at'] <= now + 7200)
    return value


def jar_history(path):
    """Same source bytes and Flyway CRC; reject duplicates/config overrides/callbacks."""
    prefix = 'BOOT-INF/classes/db/migration/'
    entries = {}
    with zipfile.ZipFile(path) as jar:
        files = [i for i in jar.infolist() if i.filename.startswith(prefix) and not i.is_dir()]
        require(0 < len(files) <= 1000 and sum(i.file_size for i in files) < 4_000_000)
        for entry in files:
            name = entry.filename[len(prefix):]
            match = re.fullmatch(r'V([1-9][0-9]{0,8})__[A-Za-z0-9_]+\.sql', name)
            require(match is not None and int(match[1]) not in entries)
            raw = jar.read(entry)
            crc = zlib.crc32(raw.decode('utf-8-sig').replace('\r', '').replace('\n', '').encode())
            entries[int(match[1])] = {'name': name, 'sha256': sha(raw), 'normalized_sha256': sha(raw.replace(b'\r\n', b'\n').replace(b'\r', b'\n')), 'crc': crc if crc < 2**31 else crc - 2**32}
    require(sorted(entries) == list(range(1, len(entries) + 1)))
    return [entries[i] for i in sorted(entries)]


def fingerprint(entries):
    digest = hashlib.sha256()
    for row in sorted(entries, key=lambda r: r['name']):
        digest.update(row['name'].encode() + b'\0' + bytes.fromhex(row['normalized_sha256']))
    return digest.hexdigest()


def configuration(cfg):
    require(cfg.get('enabled') is True and re.fullmatch(SHA, cfg.get('executor_sha256', '')))
    require(re.fullmatch(r'i-[0-9a-f]{17}', cfg.get('instance', '')))
    require(re.fullmatch(r'arn:aws:elasticloadbalancing:ap-northeast-2:494915081306:targetgroup/[A-Za-z0-9-]+/[0-9a-f]+', cfg.get('target_group', '')))
    require(type(cfg.get('drain_seconds')) is int and 0 <= cfg['drain_seconds'] <= 600)
    require(re.fullmatch(r'[a-z][a-z0-9-]{0,62}', cfg.get('rds', '')))
    require(re.fullmatch(r'[a-z0-9.-]+\.rds\.amazonaws\.com', cfg.get('db_host', '')))
    for name in ('app_user', 'migration_user', 'database_owner'):
        require(re.fullmatch(r'[a-z_][a-z0-9_]{0,62}', cfg.get(name, '')))
    require(len({cfg[k] for k in ('app_user', 'migration_user', 'database_owner')}) == 3)
    require(cfg.get('env_file') == '/opt/hobbytree/.env.ec2'
            and cfg.get('compose_file') == '/opt/hobbytree/compose.ec2.yml'
            and cfg.get('ca_file') == '/opt/hobbytree/certs/rds-ca.pem')
    required_files = {cfg['env_file'], cfg['compose_file'], cfg['ca_file'],
                      '/etc/hobbytree/ai.properties', '/etc/hobbytree/backend-cors.env'}
    require(required_files <= set(cfg.get('protected_files', {})))
    require(all(re.fullmatch(SHA, value) for value in cfg['protected_files'].values()))
    return cfg


def execute(host, request, active, record):
    """Durable states make ambiguous/failed executions ineligible for blind retries."""
    changed = request['target'] != len(active['migrations'])
    require(active['status'] == 'complete' and request['digest'] != active['digest'])
    phase = 'preflight'
    fenced = False
    migration_started = False
    try:
        host.preflight(request, active)
        for phase in ('drain', 'stop', 'fence', 'backup', 'migrate', 'validate', 'unfence', 'start', 'verify', 'publish'):
            if phase in ('backup', 'migrate') and not changed:
                continue
            record(phase, 'running')  # Before every possible side effect.
            if phase == 'migrate':
                migration_started = True
            if phase == 'fence':
                fenced = True  # Failure after REVOKE is still a fenced failure.
            host.perform(phase, request, active)
            record(phase, 'done')
        record('complete', 'complete')
        return 'complete'
    except Exception:
        record(phase, 'failed')
        # No automatic DB downgrade, repair or snapshot replacement.
        # Even a failed transactional migrate may be ambiguous to the observer.
        if phase == 'preflight':
            return 'failed'
        host.contain(request, active)
        if not migration_started and phase != 'preflight':
            try:
                host.restore(active, fenced)
                record('rollback', 'restored')
                return 'restored'
            except Exception:
                record('rollback', 'failed')
        return 'failed'


class Host:
    def __init__(self, config, directory):
        self.config, self.directory = config, directory
        self.deadline = time.monotonic() + 5400
        self.db_cid = None
        self.candidate = None

    def command(self, args, timeout=60, data=None):
        remaining = min(timeout, self.deadline - time.monotonic())
        require(remaining > 0, 'Release deadline exceeded')
        env = {'PATH': '/usr/local/bin:/usr/bin:/bin', 'HOME': '/root', 'AWS_REGION': REGION,
               'AWS_PAGER': '', 'AWS_SHARED_CREDENTIALS_FILE': '/dev/null', 'AWS_CONFIG_FILE': '/dev/null',
               'AWS_EC2_METADATA_V1_DISABLED': 'true', 'LANG': 'C.UTF-8', 'DOCKER_CONFIG': str(self.directory / 'docker')}
        return subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              env=env, timeout=remaining, check=True).stdout

    def aws(self, *args):
        raw = self.command(['aws', *args, '--region', REGION, '--output', 'json'], 120)
        return json.loads(raw) if raw.strip() else None

    def compose(self, digest):
        override = self.directory / 'override.json'
        override.write_bytes(canonical({'services': {'backend': {'image': REGISTRY + '@' + digest,
            'restart': 'no', 'pull_policy': 'never', 'labels': {'hobbytree.main-release': self.request['request']}}}}))
        return ['docker', 'compose', '--project-name', 'hobbytree', '--project-directory', '/opt/hobbytree',
                '--env-file', self.config['env_file'], '-f', self.config['compose_file'], '-f', str(override)]

    def container(self, service):
        ids = self.command(['docker', 'ps', '-aq', '--no-trunc', '--filter', 'label=com.docker.compose.project=hobbytree',
                            '--filter', 'label=com.docker.compose.service=' + service]).decode().splitlines()
        require(len(ids) == 1 and re.fullmatch(r'[0-9a-f]{64}', ids[0]), 'Container identity unavailable')
        return json.loads(self.command(['docker', 'inspect', ids[0]]))[0]

    def target(self, verb):
        return self.aws('elbv2', verb, '--target-group-arn', self.config['target_group'],
                        '--targets', 'Id=' + self.config['instance'] + ',Port=8080')

    def wait_target(self, wanted):
        end = time.monotonic() + self.config['drain_seconds'] + 180
        while time.monotonic() < end:
            rows = self.target('describe-target-health')['TargetHealthDescriptions']
            require(len(rows) == 1)
            if rows[0]['TargetHealth']['State'] == wanted:
                return
            time.sleep(3)
        raise ValueError('Target wait expired')

    def image(self, digest, image_id, revision):
        uri = REGISTRY + '@' + digest
        self.command(['docker', 'pull', uri], 600)
        obj = json.loads(self.command(['docker', 'image', 'inspect', uri]))[0]
        require(obj['Id'] == image_id and uri in obj.get('RepoDigests', []) and obj['Os'] == 'linux'
                and obj['Architecture'] == 'amd64' and obj['Config']['User'] == '10001:10001'
                and obj['Config']['Labels']['org.opencontainers.image.revision'] == revision)
        cid = self.command(['docker', 'create', '--network', 'none', '--entrypoint', '/bin/true', uri]).decode().strip()
        require(re.fullmatch(r'[0-9a-f]{64}', cid))
        jar = self.directory / (revision + '.jar')
        try:
            self.command(['docker', 'cp', cid + ':/app/hobbytree.jar', str(jar)])
            return jar_history(jar)
        finally:
            self.command(['docker', 'rm', cid])

    def preflight(self, request, active):
        self.request, self.active = request, active
        cfg = configuration(self.config)
        self.deadline = min(self.deadline, time.monotonic() + request['expires_at'] - time.time() - 60)
        require(cfg['enabled'] is True and json.loads(protected(ROOT / 'active.json').read_bytes())['status'] == 'main-release-owned')
        require(protected(Path('/var/lib/cloud/data/instance-id')).read_text().strip() == cfg['instance'])
        require(self.aws('sts', 'get-caller-identity')['Account'] == '494915081306')
        for name, checksum in cfg['protected_files'].items():
            require(sha(protected(name).read_bytes()) == checksum, 'Installed configuration drift')
        props = self.command(['systemctl', 'show', 'hobbytree-backend.service', '-p', 'ActiveState', '-p', 'UnitFileState']).decode()
        require('ActiveState=inactive' in props and 'UnitFileState=disabled' in props, 'Legacy service must be retired during approved installation')
        self.previous = self.container('backend')
        self.redis = self.container('redis')
        require(self.previous['Config']['Image'] == REGISTRY + '@' + active['digest']
                and self.previous['Image'] == active['image_id']
                and self.previous['HostConfig']['RestartPolicy']['Name'] == 'no'
                and self.previous['State']['Health']['Status'] == 'healthy')
        password = self.command(['aws', 'ecr', 'get-login-password', '--region', REGION])
        self.command(['docker', 'login', '--username', 'AWS', '--password-stdin', REGISTRY.split('/')[0]], data=password)
        before = self.image(active['digest'], active['image_id'], active['revision'])
        after = self.image(request['digest'], request['image_id'], request['revision'])
        require(before == active['migrations'] and after[:len(before)] == before and len(after) == request['target'])
        require(fingerprint(after) == request['sql_set_sha256'])
        self.after = after
        model = json.loads(self.command(self.compose(request['digest']) + ['config', '--format', 'json']))
        backend = model['services']['backend']
        require(set(model['services']) == {'backend', 'redis'} and backend['user'] == '10001:10001'
                and backend['read_only'] and backend['restart'] == 'no'
                and not any(backend.get(k) for k in ('entrypoint', 'command', 'privileged', 'build', 'post_start', 'pre_stop')))
        require(backend['environment']['SPRING_FLYWAY_ENABLED'] == 'false'
                and backend['environment']['SPRING_JPA_HIBERNATE_DDL_AUTO'] == 'validate')
        require(backend['environment']['DB_URL'] == 'jdbc:postgresql://' + cfg['db_host']
                + ':5432/hobbytree?sslmode=verify-full&sslrootcert=/etc/hobbytree/certs/rds-ca.pem')
        for key, value in backend['environment'].items():
            require(dict(x.split('=', 1) for x in self.previous['Config']['Env']).get(key) == str(value),
                    'Runtime environment differs from the approved Compose configuration')
        self.database('before')

    def database(self, mode):
        cfg = self.config
        history = self.directory / 'history.tsv'
        history.write_text(''.join(f"{i}\t{r['name']}\t{r['crc']}\n" for i, r in enumerate(self.after, 1)))
        history.chmod(0o444)
        policy = self.directory / 'database-policy.tsv'
        policy.write_text('\n'.join([cfg['database_owner'], *sorted(cfg['privileged_logins'])]) + '\n')
        policy.chmod(0o444)
        cid = self.directory / ('db-' + mode + '.cid')
        require(not cid.exists(), 'Database command replay refused')
        args = ['docker', 'run', '--rm', '--network', 'host', '--read-only', '--cap-drop', 'ALL',
                '--security-opt', 'no-new-privileges:true', '--user', '10001:10001', '--memory', '512m',
                '--pids-limit', '128', '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m', '--cidfile', str(cid),
                '--label', 'hobbytree.main-release=' + self.request['request'], '--entrypoint', 'java']
        mounts = [(history, '/run/history.tsv'), (policy, '/run/database-policy.tsv'),
                  (protected(cfg['ca_file']), '/etc/hobbytree/certs/rds-ca.pem')]
        for name in ('DB_USERNAME', 'DB_PASSWORD'):
            source = Path('/run/hobbytree/migration-secrets') / name
            info = source.lstat()
            require(stat.S_ISREG(info.st_mode) and info.st_uid == 10001 and stat.S_IMODE(info.st_mode) == 0o400)
            mounts.append((source, '/run/secrets/hobbytree/' + name))
        for src, dst in mounts:
            args += ['--mount', f'type=bind,src={src},dst={dst},readonly']
        values = {'DB_URL': 'jdbc:postgresql://' + cfg['db_host'] + ':5432/hobbytree?sslmode=verify-full&sslrootcert=/etc/hobbytree/certs/rds-ca.pem',
                  'TRANSITION_DB_HOST': cfg['db_host'], 'TRANSITION_APP_USER': cfg['app_user'],
                  'TRANSITION_MIGRATION_USER': cfg['migration_user']}
        for key, value in values.items():
            args += ['-e', key + '=' + value]
        args += [REGISTRY + '@' + self.request['digest'], '-Xmx256m',
                 '-Dloader.main=com.hobbytree.hobbytree.deployment.ReleaseMigrationCli', '-cp', '/app/hobbytree.jar',
                 'org.springframework.boot.loader.launch.PropertiesLauncher', mode, '/run/history.tsv',
                 str(len(self.active['migrations'])), '/run/database-policy.tsv']
        self.db_cid = cid
        try:
            output = self.command(args, 900)
            require(output.strip().endswith(('RELEASE_PASS=' + mode).encode()))
        finally:
            self.stop_database()

    def stop_database(self):
        if self.db_cid is None or not self.db_cid.exists():
            return
        cid = self.db_cid.read_text().strip()
        require(re.fullmatch(r'[0-9a-f]{64}', cid))
        if self.command(['docker', 'ps', '-aq', '--no-trunc', '--filter', 'id=' + cid]).strip():
            obj = json.loads(self.command(['docker', 'inspect', cid]))[0]
            require(obj['Config']['Labels']['hobbytree.main-release'] == self.request['request']
                    and obj['Image'] == self.request['image_id'])
            self.command(['docker', 'stop', '--time', '20', cid], 30)
            require(not self.command(['docker', 'ps', '-q', '--no-trunc', '--filter', 'id=' + cid]).strip())
        self.db_cid = None

    def probes(self, base):
        def query(path, headers=(), method='GET'):
            return self.command(['curl', '--silent', '--show-error', '--max-time', '10', '-X', method,
                                 '-D', '-', '-o', '/dev/null', *headers, base + path]).decode().lower()
        require(re.search(r'http/[0-9.]+ 200', query('/actuator/health')))
        require(re.search(r'http/[0-9.]+ 401', query('/api/v1/users/me')))
        cors = query('/api/v1/users/me', ['-H', 'Origin: http://localhost:8081', '-H', 'Access-Control-Request-Method: GET'], 'OPTIONS')
        require(re.search(r'http/[0-9.]+ 200', cors) and 'access-control-allow-origin: http://localhost:8081' in cors)
        denied = query('/api/v1/users/me', ['-H', 'Origin: https://invalid.example', '-H', 'Access-Control-Request-Method: GET'], 'OPTIONS')
        require('access-control-allow-origin:' not in denied)

    def perform(self, phase, request, active):
        if phase == 'drain':
            self.target('deregister-targets')
            self.wait_target('unused')
        elif phase == 'stop':
            require(self.container('backend')['Id'] == self.previous['Id'])
            self.command(['docker', 'stop', '--time', '40', self.previous['Id']], 60)
        elif phase in ('fence', 'migrate', 'validate', 'unfence'):
            self.database(phase)
        elif phase == 'backup':
            cfg = self.config
            db = self.aws('rds', 'describe-db-instances', '--db-instance-identifier', cfg['rds'])['DBInstances']
            require(len(db) == 1 and db[0]['StorageEncrypted'] and db[0]['DBInstanceStatus'] == 'available'
                    and db[0]['Endpoint']['Address'] == cfg['db_host'])
            name = 'hobbytree-main-' + request['request']
            self.aws('rds', 'create-db-snapshot', '--db-instance-identifier', cfg['rds'], '--db-snapshot-identifier', name)
            self.command(['aws', 'rds', 'wait', 'db-snapshot-available', '--region', REGION, '--db-snapshot-identifier', name], 1800)
            snap = self.aws('rds', 'describe-db-snapshots', '--db-snapshot-identifier', name)['DBSnapshots']
            require(len(snap) == 1 and snap[0]['Status'] == 'available' and snap[0]['Encrypted']
                    and snap[0]['DBInstanceIdentifier'] == cfg['rds'])
            save(self.directory / 'snapshot.json', {'arn': snap[0]['DBSnapshotArn']})
        elif phase == 'start':
            self.command(self.compose(request['digest']) + ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '240', 'backend'], 300)
            self.candidate = self.container('backend')['Id']
        elif phase == 'verify':
            current = self.container('backend')
            require(current['Image'] == request['image_id'] and current['State']['Health']['Status'] == 'healthy')
            require(self.container('redis')['Id'] == self.redis['Id'] and self.container('redis')['Mounts'] == self.redis['Mounts'])
            self.probes('http://127.0.0.1:8080')
        elif phase == 'publish':
            self.target('register-targets')
            self.wait_target('healthy')
            for _ in range(15):
                self.probes('https://api.hobbytreeapp.com')
                require(self.container('backend')['Image'] == request['image_id'])
                time.sleep(10)

    def contain(self, request, active):
        # Independent bounded cleanup time, never an extension for migrations.
        self.deadline = time.monotonic() + 600
        self.stop_database()
        if not hasattr(self, 'previous'):
            return
        self.target('deregister-targets')
        self.wait_target('unused')
        current = self.container('backend')
        owned = current['Id'] == self.previous['Id'] or (
            current['Image'] == request['image_id'] and current['Config'].get('Labels', {}).get('hobbytree.main-release') == request['request'])
        require(owned, 'Unexpected container during containment')
        self.command(['docker', 'stop', '--time', '40', current['Id']], 60)

    def restore(self, active, fenced):
        if fenced:
            self.database('restore-before')
        self.command(self.compose(active['digest']) + ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '240', 'backend'], 300)
        require(self.container('backend')['Image'] == active['image_id'])
        self.probes('http://127.0.0.1:8080')
        self.target('register-targets')
        self.wait_target('healthy')
        self.probes('https://api.hobbytreeapp.com')


def main():
    import fcntl
    require(os.geteuid() == 0 and len(sys.argv) == 2 and len(sys.argv[1]) <= 4096)
    request = payload(json.loads(base64.b64decode(sys.argv[1], validate=True)))
    config = json.loads(protected(CONFIG).read_bytes())
    require(config['enabled'] is True and config['executor_sha256'] == sha(protected(__file__).read_bytes()))
    protected(STATE)
    with open(protected(ROOT / 'lock'), 'rb') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        active = json.loads(protected(STATE / 'active.json').read_bytes())
        require(active['status'] == 'complete', 'Previous release requires reconciliation')
        directory = STATE / request['request']
        directory.mkdir(mode=0o700)
        (directory / 'docker').mkdir(mode=0o700)
        save(directory / 'request.json', request)
        save(directory / 'previous.json', active)
        host = Host(config, directory)
        events = []
        def record(phase, status):
            events.append({'phase': phase, 'status': status, 'at': int(time.time())})
            save(directory / 'journal.json', {'request': request, 'events': events})
            if status == 'complete':
                save(STATE / 'active.json', {**request, 'migrations': host.after, 'status': 'complete'})
            elif status == 'restored':
                save(STATE / 'active.json', active)
            else:
                save(STATE / 'active.json', {**active, 'status': 'blocked', 'request': request['request']})
        try:
            result = execute(host, request, active, record)
        finally:
            credential = directory / 'docker/config.json'
            if credential.exists():
                credential.unlink()
        print('RELEASE_RESULT=' + result)
        return 0 if result == 'complete' else (10 if result == 'restored' else 20)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        print('RELEASE_FAILED_RECONCILE_HOST_JOURNAL', file=sys.stderr)
        sys.exit(20)
