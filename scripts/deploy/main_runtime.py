#!/usr/bin/env python3
"""Restore only a completed release; never migrate, unfence or replay a deployment."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import time


def require(ok):
    if not ok:
        raise ValueError('Runtime recovery invariant failed')


def protected(path):
    path = Path(path)
    for item in [path, *path.parents]:
        info = item.lstat()
        require(info.st_uid == 0 and not stat.S_ISLNK(info.st_mode) and not info.st_mode & 0o022)
    return path


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def settings(config):
    runtime = config['runtime']
    require(runtime['enabled'] is True)
    for key in ('app_secret', 'auth_secret', 'migration_secret'):
        require(re.fullmatch(r'arn:aws:secretsmanager:ap-northeast-2:494915081306:secret:[A-Za-z0-9/_+=.@-]+', runtime[key]))
    require(len({runtime[k] for k in ('app_secret', 'auth_secret', 'migration_secret')}) == 3)
    return runtime


def recover(adapter, active, record):
    require(active.get('status') == 'complete')
    adapter.preflight(active)  # No mutation if configuration or image ownership is unknown.
    if adapter.healthy():
        return 'healthy'
    if not adapter.attempt():
        return 'rate_limited'
    adapter.record = record
    try:
        for phase in ('isolate', 'stop', 'secrets', 'database', 'redis', 'backend', 'verify', 'publish'):
            record(phase, 'running')
            adapter.perform(phase)
            record(phase, 'done')
        return 'recovered'
    except Exception:
        try:
            record(phase, 'failed')
        except Exception:
            pass  # Isolation still runs when the evidence disk is unavailable.
        try:
            adapter.contain()
            outcome = 'done'
        except Exception:
            outcome = 'failed'
        try:
            record('containment', outcome)
        except Exception:
            pass
        raise


class Runtime:
    def __init__(self, host, fetch):
        self.host, self.fetch = host, fetch
        self.cfg = host.config
        self.record = lambda *_: None

    def preflight(self, active):
        h = self.host
        self.runtime = settings(self.cfg)
        h.active = h.request = active
        h.after = active['migrations']
        require(active['sql_set_sha256'] == h.fingerprint(h.after))
        for name, checksum in self.cfg['protected_files'].items():
            require(hashlib.sha256(protected(name).read_bytes()).hexdigest() == checksum)
        require(protected('/var/lib/cloud/data/instance-id').read_text().strip() == self.cfg['instance'])
        require(json.loads(protected(h.root / 'active.json').read_bytes())['status'] == 'main-release-owned')
        unit = h.command(['systemctl', 'show', 'hobbytree-backend.service', '-p', 'ActiveState', '-p', 'UnitFileState']).decode()
        require('ActiveState=inactive' in unit and 'UnitFileState=disabled' in unit)
        self.previous = h.container('backend', allow_absent=True)
        if self.previous:
            require(self.previous['Image'] == active['image_id'] and self.previous['Config']['Image'] == h.registry + '@' + active['digest'])
            require(self.previous['HostConfig']['RestartPolicy']['Name'] == 'no')
        self.model = json.loads(h.command(h.compose(active['digest']) + ['config', '--format', 'json']))
        backend = self.model['services']['backend']
        require(set(self.model['services']) == {'backend', 'redis'})
        require(backend['user'] == '10001:10001' and backend['read_only'])
        require(not any(backend.get(k) for k in ('entrypoint', 'command', 'privileged', 'build', 'post_start', 'pre_stop')))
        env = backend['environment']
        require(env['SPRING_FLYWAY_ENABLED'] == 'false' and env['SPRING_JPA_HIBERNATE_DDL_AUTO'] == 'validate')
        require(env['DB_URL'] == 'jdbc:postgresql://' + self.cfg['db_host'] + ':5432/hobbytree?sslmode=verify-full&sslrootcert=/etc/hobbytree/certs/rds-ca.pem')
        if self.previous:
            actual = self.previous
            require(actual['Config']['User'] == '10001:10001' and actual['HostConfig']['ReadonlyRootfs']
                    and not actual['HostConfig']['Privileged'])
            image = json.loads(h.command(['docker', 'image', 'inspect', actual['Image']]))[0]
            require(all(actual['Config'].get(k) == image['Config'].get(k) for k in ('Entrypoint', 'Cmd')))
            values = dict(item.split('=', 1) for item in actual['Config']['Env'])
            require(all(values.get(k) == str(v) for k, v in env.items()))
        mounts = {m['target']: m['source'] for m in backend['volumes'] if m['type'] == 'bind'}
        self.app = mounts['/run/secrets/hobbytree']
        redis = self.model['services']['redis']
        self.redis_dir = next(m['source'] for m in redis['volumes'] if m['target'] == '/run/secrets/redis')
        require(self.app == '/run/hobbytree/secrets' and self.redis_dir == '/run/hobbytree/redis-secrets')
        require(redis['restart'] == 'no')  # Installed Compose must prevent Docker racing /run preparation.
        require(any(m['type'] == 'volume' and m['target'] == '/data' and m['source'] == 'redis_data' for m in redis['volumes']))
        require(self.model['volumes']['redis_data']['name'] == 'hobbytree_redis_data')
        h.command(['docker', 'volume', 'inspect', 'hobbytree_redis_data'])  # Never replace lost data with an empty volume.
        self.redis_before = h.container('redis', allow_absent=True)
        if self.redis_before:
            require(self.redis_before['Config']['Image'] == redis['image'])
            require(self.redis_before['HostConfig']['RestartPolicy']['Name'] == 'no')
            require(any(m.get('Name') == 'hobbytree_redis_data' and m['Destination'] == '/data' for m in self.redis_before['Mounts']))

    def healthy(self):
        state_path = self.host.root / 'main-release/runtime-status.json'
        if state_path.exists():
            state = json.loads(protected(state_path).read_bytes())
            if state.get('release') == self.host.active['request'] and state.get('digest') == self.host.active['digest']:
                if (state.get('phase'), state.get('status')) != ('publish', 'done'):
                    return False
        if not all(obj and obj['State'].get('Running') and obj['State'].get('Health', {}).get('Status') == 'healthy'
                   for obj in (self.previous, self.redis_before)):
            return False
        try:
            self.fetch.VALIDATOR.validate_secret_files(self.app, self.redis_dir)
            for name in ('DB_USERNAME', 'DB_PASSWORD'):
                self.fetch.VALIDATOR.read_protected_file(Path('/run/hobbytree/migration-secrets') / name)
            self.host.probes('http://127.0.0.1:8080')
        except Exception:
            return False
        # A failed control-plane read is not evidence that a running app should be stopped.
        rows = self.host.target('describe-target-health')['TargetHealthDescriptions']
        if len(rows) != 1 or rows[0]['TargetHealth']['State'] != 'healthy':
            return False
        try:
            self.host.probes('https://api.hobbytreeapp.com')
            return True
        except Exception:
            return False

    def attempt(self):
        path = self.host.root / 'main-release/runtime-attempts.json'
        now = int(time.time())
        state = json.loads(protected(path).read_bytes()) if path.exists() else {}
        times = state.get('times', []) if state.get('digest') == self.host.active['digest'] else []
        require(all(type(t) is int for t in times))
        times = [t for t in times if t > now - 600]
        if len(times) >= 3:
            return False
        self.host.save(path, {'digest': self.host.active['digest'], 'times': times + [now]})
        return True

    def prepare(self):
        f, r, h = self.fetch, self.runtime, self.host
        # Fetch every value before any file replacement. No stale/offline fallback.
        app = f.read_ai_credentials(f.AI_SOURCE, self.app)
        app.update(f.read_naver_credentials(f.NAVER_SOURCE, self.app))
        app.update(f.read_kakao_credentials(f.KAKAO_SOURCE, self.app))
        app.update(f.read_credentials(r['app_secret'], 'ap-northeast-2', self.cfg['app_user']))
        app.update(f.read_auth_credentials(r['auth_secret'], 'ap-northeast-2'))
        migration = f.read_credentials(r['migration_secret'], 'ap-northeast-2', self.cfg['migration_user'])
        redis = {'REDIS_PASSWORD': app['REDIS_PASSWORD'], 'users.acl': 'user default on #' + hashlib.sha256(app['REDIS_PASSWORD'].encode()).hexdigest() + ' ~auth:* +@all\n'}
        f.write_credentials(self.redis_dir, redis, uid=999)
        f.write_credentials(self.app, app)
        f.write_credentials('/run/hobbytree/migration-secrets', migration)
        f.VALIDATOR.validate_secret_files(self.app, self.redis_dir)
        password = h.command(['aws', 'ecr', 'get-login-password', '--region', 'ap-northeast-2'])
        h.command(['docker', 'login', '--username', 'AWS', '--password-stdin', h.registry.split('/')[0]], data=password)
        try:
            require(h.image(h.active['digest'], h.active['image_id'], h.active['revision']) == h.after)
        finally:
            jar = h.directory / (h.active['revision'] + '.jar')
            if jar.exists():
                jar.unlink()

    def stop_owned(self):
        h = self.host
        failures = []
        for service, image in (('backend', h.registry + '@' + h.active['digest']), ('redis', self.model['services']['redis']['image'])):
            try:
                obj = h.container(service, allow_absent=True)
                if obj:
                    require(obj['Config']['Image'] == image)
                    h.command(['docker', 'stop', '--time', '40', obj['Id']], 60)
            except Exception:
                failures.append(service)
        require(not failures)

    def perform(self, phase):
        h = self.host
        if phase == 'isolate':
            h.target('deregister-targets')
            h.wait_target('unused')
        elif phase == 'stop':
            self.stop_owned()
        elif phase == 'secrets':
            self.prepare()
        elif phase == 'database':
            h.database('runtime-check')
        elif phase in ('redis', 'backend'):
            h.command(h.compose(h.active['digest']) + ['up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                '--force-recreate', '--wait', '--wait-timeout', '240', phase], 300)
        elif phase == 'verify':
            require(h.container('backend')['Image'] == h.active['image_id'])
            obj = h.container('redis')
            require(any(m.get('Name') == 'hobbytree_redis_data' and m['Destination'] == '/data' for m in obj['Mounts']))
            h.probes('http://127.0.0.1:8080')
        elif phase == 'publish':
            h.target('register-targets')
            h.wait_target('healthy')
            h.probes('https://api.hobbytreeapp.com')

    def contain(self):
        failures = []
        for name, budget, operation in [('database-stop', 90, self.host.stop_database),
                                ('alb-isolate', 840, lambda: self.perform('isolate')),
                                ('containers-stop', 150, self.stop_owned)]:
            self.host.deadline = time.monotonic() + budget
            try:
                operation()
                result = 'done'
            except Exception:
                result = 'failed'
                failures.append(name)
            # A journal I/O error must not prevent the remaining isolation attempts.
            try:
                self.record(name, result)
            except Exception:
                failures.append('journal')
        require(not failures)


def main():
    import fcntl
    require(os.geteuid() == 0 and len(sys.argv) == 1)
    config = json.loads(protected('/etc/hobbytree/main-release.json').read_bytes())
    runtime = settings(config)
    installed = {'runtime': Path(__file__), 'host': Path(__file__).with_name('main_release_host.py'),
                 'fetch': Path(__file__).parents[1] / 'fetch-ec2-secrets.py',
                 'validator': Path(__file__).parents[1] / 'validate-ec2-config.py'}
    for key, path in installed.items():
        require(hashlib.sha256(protected(path).read_bytes()).hexdigest() == runtime['sources'][key])
    os.environ.clear()
    os.environ.update(PATH='/usr/local/bin:/usr/bin:/bin', HOME='/root', LANG='C.UTF-8',
        AWS_SHARED_CREDENTIALS_FILE='/dev/null', AWS_CONFIG_FILE='/dev/null', AWS_EC2_METADATA_V1_DISABLED='true')
    h = load('release_host', installed['host'])
    h.configuration(config)
    require(config['executor_sha256'] == runtime['sources']['host'])
    fetch = load('runtime_fetch', installed['fetch'])
    with open(protected(h.ROOT / 'lock'), 'rb') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print('RUNTIME_BUSY')
            return
        active = json.loads(protected(h.STATE / 'active.json').read_bytes())
        require(active['status'] == 'complete')
        h.payload({k: active[k] for k in ('format', 'revision', 'digest', 'image_id', 'sql_set_sha256', 'target', 'request')} | {'expires_at': int(time.time()) + 60})
        require(len(active['migrations']) == active['target'])
        directory = Path(tempfile.mkdtemp(prefix='runtime-', dir=protected(h.STATE)))
        (directory / 'docker').mkdir(mode=0o700)
        host = h.Host(config, directory)
        host.root, host.registry, host.fingerprint, host.save = h.ROOT, h.REGISTRY, h.fingerprint, h.save
        events = []
        def record(phase, status):
            events.append({'phase': phase, 'status': status, 'at': int(time.time())})
            h.save(directory / 'journal.json', {'revision': active['revision'], 'events': events})
            h.save(h.STATE / 'runtime-status.json', {'release': active['request'], 'digest': active['digest'],
                                                   'phase': phase, 'status': status})
        try:
            result = recover(Runtime(host, fetch), active, record)
            print('RUNTIME_' + result.upper())
        finally:
            credential = directory / 'docker/config.json'
            if credential.exists():
                credential.unlink()
            if not events:
                (directory / 'override.json').unlink(missing_ok=True)
                (directory / 'docker').rmdir()
                directory.rmdir()


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('RUNTIME_RECOVERY_FAILED_CHECK_PROTECTED_JOURNAL', file=sys.stderr)
        sys.exit(1)
