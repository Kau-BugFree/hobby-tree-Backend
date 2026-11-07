#!/usr/bin/env python3
"""PROPOSED separate SSM path: quiesce, approved DB change, approved image activation.

Never called by SSM v2. Install only after review and explicit AWS approval.
No image rollback after any DB attempt; a nonterminal journal blocks legacy release tools.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import time

SPEC = importlib.util.spec_from_file_location('release', Path(__file__).with_name('ec2-release.py'))
HOST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST)
require = HOST.require
PLANS = Path('/etc/hobbytree/transitions')
APP_SECRETS = Path('/run/hobbytree/secrets')
OWNER_SECRETS = Path('/run/hobbytree/migration-secrets')
APPROVALS = {'quiesce': 'approve-maintenance-and-backup-window',
             'migrate': 'approve-backed-up-schema-change-no-image-rollback',
             'deploy': 'approve-verified-schema-image-activation'}
EXPECTED_STATE = {'migrate': 'quiesced', 'deploy': 'db-verified'}


def validate_plan(plan, request):
    """Only public fixed identifiers, a bounded lifetime and exact rehearsal history."""
    require(plan.get('format') == 1 and plan.get('request') == request, 'Plan identity mismatch.')
    require(plan.get('account') == '494915081306' and plan.get('region') == HOST.REGION
            and plan.get('instance') == HOST.INSTANCE and plan.get('rds') == 'hobbytree-dev-private', 'Plan target mismatch.')
    require(re.fullmatch(r'[0-9a-f]{40}', plan.get('revision', '')) is not None, 'Invalid revision.')
    for key in ('candidate', 'previous'):
        require(HOST.DIGEST.fullmatch(plan.get(key, '')) is not None, 'Invalid image digest.')
    require(plan['candidate'] != plan['previous'], 'Distinct images required.')
    for key in ('before_schema', 'after_schema', 'host_helper_sha256', 'test_report_sha256'):
        require(re.fullmatch(r'[0-9a-f]{64}', plan.get(key, '')) is not None, 'Missing evidence hash.')
    require(type(plan.get('expires_at')) is int and time.time() < plan['expires_at'] <= time.time() + 7200,
            'Plan expired or lifetime exceeds two hours.')
    require(re.fullmatch(r'[a-z0-9.-]+\.rds\.amazonaws\.com', plan.get('db_host', '')) is not None, 'RDS endpoint required.')
    require(re.fullmatch(r'[a-z_][a-z0-9_]{0,62}', plan.get('app_user', '')) is not None, 'App role required.')
    require(re.fullmatch(r'hobbytree-v5-[a-z0-9-]{1,100}', plan.get('snapshot', '')) is not None, 'New named V5 snapshot required.')
    require(plan.get('writers_fenced') is True, 'All other DB writers must be reviewed and fenced.')
    history = plan.get('history', [])
    baseline = plan.get('baseline')
    require(type(baseline) is int and 0 < baseline < len(history) <= 1000, 'Invalid migration baseline.')
    for version, row in enumerate(history, 1):
        require(re.fullmatch(str(version) + r'\tV' + str(version) + r'__[A-Za-z0-9_]+\.sql\t-?[0-9]+', row) is not None,
                'Noncontiguous or invalid history.')
    return plan


@contextmanager
def locked(request, phase, plan_sha):
    """Share the existing host lock; only this exact nonterminal request can resume."""
    import fcntl
    require(sys.platform == 'linux' and os.geteuid() == 0, 'Linux root required.')
    HOST.protected(HOST.STATE, directory=True)
    descriptor = os.open(HOST.STATE / 'lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'a+b') as stream:
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        active = HOST.STATE / 'active.json'
        HOST.protected(active)
        state = json.loads(active.read_text())
        if phase == 'quiesce':
            require(state.get('status') in HOST.TERMINAL, 'Existing release requires recovery.')
        else:
            require(state.get('request') == request and state.get('plan_sha256') == plan_sha
                    and state.get('status') == EXPECTED_STATE[phase], 'Wrong phase, replay or unresolved transition.')
        yield state


class Engine:
    def __init__(self, plan, commands, temporary, plan_sha):
        self.plan, self.cmd, self.plan_sha = plan, commands, plan_sha
        self.release = HOST.Deployment(commands, temporary, plan['candidate'], plan['previous'], plan['revision'], plan['request'])
        self.directory = self.release.directory
        self.record = {'request': plan['request'], 'plan_sha256': plan_sha, 'status': 'preparing', 'terminal_status': False}

    def journal(self, state):
        self.record['status'] = state
        self.record['terminal_status'] = state == 'succeeded'
        data = json.dumps(self.record, sort_keys=True).encode()
        HOST.atomic_write(self.directory / 'transition.json', data)
        HOST.atomic_write(HOST.STATE / 'active.json', data)

    def aws(self, *args, timeout=30):
        return self.cmd.json(['aws', *args, '--region', HOST.REGION, '--output', 'json'], self.release.deadline, timeout)

    def target(self, verb):
        self.cmd.run(['aws', 'elbv2', verb, '--region', HOST.REGION, '--target-group-arn', HOST.TARGET_GROUP,
                      '--targets', 'Id=' + HOST.INSTANCE + ',Port=8080'], self.release.deadline)

    def protected_inputs(self):
        HOST.protected(HOST.CONTRACT)
        contract = json.loads(HOST.CONTRACT.read_text())
        require(set(contract['files']) == {str(HOST.ROOT / 'compose.ec2.yml'), str(HOST.ROOT / 'scripts/validate-ec2-config.py'),
                                          '/etc/systemd/system/' + HOST.UNIT}, 'Unexpected host contract.')
        for name, digest in contract['files'].items():
            HOST.protected(name)
            require(HOST.sha(Path(name).read_bytes()) == digest, 'Host contract drift.')
        HOST.protected(HOST.ENV_FILE)
        require(HOST.sha(HOST.ENV_FILE.read_bytes()) == self.record['settings_sha256'], 'Settings changed during maintenance.')
        require(self.release.extract_schema(HOST.REPOSITORY + '@' + self.plan['candidate'], 'candidate') == self.plan['after_schema'], 'Candidate SQL changed.')

    def migration(self, mode):
        """Run the fixed main class, with file-only credentials and no app entrypoint."""
        source = APP_SECRETS if mode == 'validate-app' else OWNER_SECRETS
        HOST.protected(source.parent, directory=True)
        require(not source.is_symlink() and source.is_dir() and source.stat().st_uid == 10001
                and stat.S_IMODE(source.stat().st_mode) == 0o500, 'Credential directory ownership/mode mismatch.')
        args = ['docker', 'run', '--rm', '--name', 'hobbytree-transition-' + self.plan['request'], '--pull', 'never',
                '--platform', 'linux/amd64', '--network', 'host', '--read-only', '--cap-drop', 'ALL',
                '--security-opt', 'no-new-privileges:true', '--user', '10001:10001', '--memory', '512m',
                '--pids-limit', '128', '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m', '--entrypoint', 'java']
        for name in ('DB_USERNAME', 'DB_PASSWORD'):
            path = source / name
            require(not path.is_symlink() and path.is_file(), 'Credential file missing.')
            info = path.stat()
            require(info.st_uid == 10001 and stat.S_IMODE(info.st_mode) == 0o400, 'Credential file ownership/mode mismatch.')
            args += ['--mount', 'type=bind,src=' + str(path) + ',dst=/run/secrets/hobbytree/' + name + ',readonly']
        ca = HOST.ROOT / 'certs/rds-ca.pem'
        HOST.protected(ca)
        args += ['--mount', 'type=bind,src=' + str(ca) + ',dst=/etc/hobbytree/certs/rds-ca.pem,readonly',
                 '--mount', 'type=bind,src=' + str(self.directory / 'history.tsv') + ',dst=/run/history.tsv,readonly',
                 '-e', 'DB_URL=' + self.record['db_url'], '-e', 'TRANSITION_DB_HOST=' + self.plan['db_host'],
                 '-e', 'TRANSITION_APP_USER=' + self.plan['app_user'], HOST.REPOSITORY + '@' + self.plan['candidate'],
                 '-Xmx256m', '-Dloader.main=com.hobbytree.hobbytree.deployment.SchemaMigrationCli',
                 '-cp', '/app/hobbytree.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher',
                 mode, '/run/history.tsv', str(self.plan['baseline'])]
        result = self.cmd.run(args, self.release.deadline, 900)
        require(result.strip().endswith(('TRANSITION_PASS=' + mode).encode()), 'Migration verifier did not complete.')

    def prepare(self, release, old_schema, new_schema):
        """Adapter for existing pre-pull/image/host verification, not the SSM v2 path."""
        require(old_schema == self.plan['before_schema'] and new_schema == self.plan['after_schema'], 'Image SQL evidence mismatch.')

    def quiesce(self):
        r = self.release
        require(not self.directory.exists(), 'Request already used.')
        instances = self.aws('rds', 'describe-db-instances', '--db-instance-identifier', self.plan['rds']).get('DBInstances', [])
        require(len(instances) == 1 and instances[0].get('DBInstanceStatus') == 'available'
                and instances[0].get('Endpoint', {}).get('Address') == self.plan['db_host']
                and instances[0].get('DBInstanceArn', '').startswith('arn:aws:rds:' + HOST.REGION + ':494915081306:db:'),
                'Live RDS endpoint/account does not match the approved database.')
        r.verify_contract()
        r.migration_gate = self
        r.prepare()
        available = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:')))
        require(available >= 512 * 1024, 'At least 512 MiB available memory required.')
        expected_url = 'jdbc:postgresql://' + self.plan['db_host'] + ':5432/hobbytree?sslmode=verify-full&sslrootcert=/etc/hobbytree/certs/rds-ca.pem'
        require(r.compose_env['DB_URL'] == expected_url, 'Live backend and plan DB URLs differ.')
        require((APP_SECRETS / 'DB_USERNAME').read_text().strip() == self.plan['app_user'], 'Application role mismatch.')
        self.directory.mkdir(mode=0o700)
        HOST.atomic_write(self.directory / 'settings.before', r.original)
        # Public SQL history must be readable by UID 10001 inside an otherwise read-only container.
        history = self.directory / 'history.tsv'
        HOST.atomic_write(history, ('\n'.join(self.plan['history']) + '\n').encode())
        os.chmod(history, 0o444)
        self.record.update(settings_sha256=HOST.sha(r.original), db_url=expected_url,
                           redis_id=r.redis['Id'], redis_mounts=r.redis['Mounts'], previous_image_id=r.old_image_id,
                           image_id=r.new_image_id)
        self.migration('check-before')
        self.journal('quiescing')
        # Persistently fence both reboot paths before starting the backup window.
        self.cmd.run(['systemctl', 'disable', HOST.UNIT], r.deadline)
        self.cmd.run(['docker', 'update', '--restart=no', 'hobbytree-backend-1'], r.deadline)
        self.target('deregister-targets')
        self.cmd.run(['aws', 'elbv2', 'wait', 'target-deregistered', '--region', HOST.REGION,
                      '--target-group-arn', HOST.TARGET_GROUP, '--targets', 'Id=' + HOST.INSTANCE + ',Port=8080'], r.deadline, 420)
        self.cmd.run(['systemctl', 'stop', HOST.UNIT], r.deadline, 60)
        self.stopped()
        self.record['stopped_at'] = datetime.now(timezone.utc).isoformat()
        self.journal('quiesced')

    def stopped(self):
        r = self.release
        info = r.container('backend')
        require(not info['State']['Running'] and info['Image'] == self.record['previous_image_id'], 'Previous backend must remain stopped.')
        require(info['HostConfig']['RestartPolicy']['Name'] == 'no', 'Previous backend reboot policy not fenced.')
        state = self.cmd.run(['systemctl', 'show', HOST.UNIT, '--property=ActiveState,UnitFileState'], r.deadline).decode()
        require('ActiveState=inactive' in state and 'UnitFileState=disabled' in state, 'Systemd maintenance fence lost.')
        redis = r.health('redis')
        require(redis['Id'] == self.record['redis_id'] and sorted(json.dumps(x, sort_keys=True) for x in redis['Mounts']) == sorted(json.dumps(x, sort_keys=True) for x in self.record['redis_mounts']), 'Redis changed during maintenance.')

    def migrate(self):
        self.protected_inputs()
        self.stopped()
        response = self.aws('rds', 'describe-db-snapshots', '--db-snapshot-identifier', self.plan['snapshot'])
        snapshots = response.get('DBSnapshots', [])
        require(len(snapshots) == 1, 'Snapshot not found.')
        snapshot = snapshots[0]
        require(snapshot.get('Status') == 'available' and snapshot.get('SnapshotType') == 'manual'
                and snapshot.get('DBInstanceIdentifier') == self.plan['rds']
                and snapshot.get('DBSnapshotArn', '').startswith('arn:aws:rds:' + HOST.REGION + ':494915081306:snapshot:'), 'Wrong or incomplete snapshot.')
        require(datetime.fromisoformat(snapshot['SnapshotCreateTime'].replace('Z', '+00:00')) >= datetime.fromisoformat(self.record['stopped_at']), 'Snapshot predates the write fence.')
        self.migration('check-before')
        self.record['snapshot_arn'] = snapshot['DBSnapshotArn']
        self.journal('db-changing')
        self.migration('migrate')
        self.migration('check-after')
        self.migration('validate-app')
        self.journal('db-verified')

    def deploy(self):
        self.protected_inputs()
        self.stopped()
        self.migration('check-after')
        self.migration('validate-app')
        r = self.release
        r.backend_config = json.loads(r.compose('config', '--format', 'json'))['services']['backend']
        HOST.runtime_controls(r.backend_config['environment'])
        # Existing stable Docker/ALB health verifier; its Redis check uses the saved identity.
        r.redis = {'Id': self.record['redis_id'], 'Mounts': self.record['redis_mounts']}
        r.contract = json.loads(HOST.CONTRACT.read_text())
        self.journal('image-changing')
        HOST.atomic_write(HOST.ENV_FILE, HOST.image_in_env((self.directory / 'settings.before').read_bytes(), r.image))
        self.cmd.run(['systemctl', 'start', HOST.UNIT], r.deadline, 360)
        r.health('backend', self.record['image_id'])
        self.target('register-targets')
        r.verify_running(self.record['image_id'])
        self.cmd.run(['systemctl', 'enable', HOST.UNIT], r.deadline)
        self.journal('succeeded')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request', required=True)
    parser.add_argument('--plan-sha256', required=True)
    parser.add_argument('--phase', choices=tuple(APPROVALS), required=True)
    parser.add_argument('--approval', required=True)
    args = parser.parse_args()
    engine = None
    try:
        require(re.fullmatch(r'[0-9]{1,20}-[0-9]{1,5}', args.request) is not None, 'Invalid request.')
        require(re.fullmatch(r'[0-9a-f]{64}', args.plan_sha256) is not None, 'Invalid plan hash.')
        require(args.approval == APPROVALS[args.phase], 'Separate phase approval required.')
        plan_file = PLANS / (args.request + '.json')
        HOST.protected(plan_file)
        require(plan_file.stat().st_size < 131072 and HOST.sha(plan_file.read_bytes()) == args.plan_sha256, 'Plan hash mismatch.')
        plan = validate_plan(json.loads(plan_file.read_text()), args.request)
        helper = Path(__file__).with_name('ec2-release.py')
        HOST.protected(helper)
        require(HOST.sha(helper.read_bytes()) == plan['host_helper_sha256'], 'Host verifier changed.')
        with locked(args.request, args.phase, args.plan_sha256) as state, tempfile.TemporaryDirectory(prefix='schema-transition-', dir='/run') as temporary:
            engine = Engine(plan, HOST.Commands(temporary), temporary, args.plan_sha256)
            if args.phase != 'quiesce':
                engine.record = dict(state)
            getattr(engine, args.phase)()
        print('PASS: approved transition phase completed.')
        return 0
    except BaseException:
        if engine is not None and engine.record.get('status') in {'quiescing', 'db-changing', 'image-changing'}:
            # No attempt to restart V5 on a changed or uncertain schema.
            try:
                engine.cmd.run(['systemctl', 'stop', HOST.UNIT], time.monotonic() + 90, 60)
                engine.cmd.run(['docker', 'update', '--restart=no', 'hobbytree-backend-1'], time.monotonic() + 30)
                engine.release.deadline = time.monotonic() + 30
                engine.target('deregister-targets')
            except BaseException:
                pass
            try: engine.journal('recovery-required')
            except BaseException: pass
        print('FAIL: phase did not complete; retain maintenance and reconcile the protected journal; no automatic image rollback.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
