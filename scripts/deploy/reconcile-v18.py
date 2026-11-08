#!/usr/bin/env python3
"""PROPOSED one-case, independently approved V18 handoff. No migration mode exists.

Install separately only after review. Never edits the original plan/request journal.
inspect: protected metadata only; assess: read-only DB/AWS probes; reconcile: signed handoff.
"""
import argparse
from contextlib import contextmanager
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import time

LEGACY = Path('/opt/hobbytree/scripts/deploy-schema')
SOURCE_REQUEST = '20261108180016-50666'
SOURCE_PLAN_SHA = '4866957adc987b9d58a99d6f35c6a76e049334571b79f43a3fde57cceed3eab7'
REVISION = '5ed331ace62b07e06d2d1cd3679f948e629f715b'
PUBLIC_SHA = '08f5c0df6bfbfadc4ec2a96b81e0274bcf46cb3df7f02f39cdd16622804e22a2'
PINNED = {'schema-transition.py': '7c6e0afc273b7df3c0ccd75546a417d0a3031abf1e6cd816ac1871a9c8e4fef5',
          'ec2-release.py': '87c56667002ac8bc85e90b9a11953f5a06c4845913f86f9b8d14610c35e81cbd'}
T = None
STAGE = 'input'


def require(ok):
    if not ok: raise ValueError('Reconciliation invariant failed')


def load_legacy():
    global T
    import hashlib
    for name, digest in PINNED.items():
        p = LEGACY / name
        for parent in (p, *p.parents):
            info = parent.lstat()
            require(not stat.S_ISLNK(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022)
        require(hashlib.sha256(p.read_bytes()).hexdigest() == digest)
    spec = importlib.util.spec_from_file_location('pinned_transition', LEGACY / 'schema-transition.py')
    T = importlib.util.module_from_spec(spec); spec.loader.exec_module(T)


def read(path, limit=262144):
    T.HOST.protected(path)
    require(Path(path).stat().st_size <= limit)
    return Path(path).read_bytes()


def canonical(body):
    return (json.dumps(body, sort_keys=True, indent=2) + '\n').encode()


def context():
    """Historical identity is checked without granting expired execution authority."""
    plan_raw = read(T.PLANS / (SOURCE_REQUEST + '.json'), 131071)
    require(T.HOST.sha(plan_raw) == SOURCE_PLAN_SHA)
    source = json.loads(plan_raw)
    require(source['request'] == SOURCE_REQUEST and source['revision'] == REVISION
            and source['baseline'] == 5 and len(source['history']) == 18)
    active_raw = read(T.HOST.STATE / 'active.json')
    journal_raw = read(T.HOST.STATE / SOURCE_REQUEST / 'transition.json')
    require(active_raw == journal_raw)
    state = json.loads(active_raw)
    require(state.get('request') == SOURCE_REQUEST and state.get('plan_sha256') == SOURCE_PLAN_SHA
            and state.get('status') == 'recovery-required' and state.get('terminal_status') is False
            and state.get('containment_failures') == [])
    require(re.fullmatch('[0-9a-f]{64}', state.get('fence_sha256', '')) is not None)
    # An approved migrate attempt, not an operator-synthesized state, must exist.
    for phase in ('quiesce', 'verify-writers', 'retire-guard', 'migrate'):
        entry = state.get('phase_approvals', {}).get(phase, {})
        aid = entry.get('id', '')
        require(re.fullmatch('[0-9a-f]{32}', aid) is not None)
        require(re.fullmatch('[0-9a-f]{64}', entry.get('sha256', '')) is not None)
        marker = json.loads(read(T.HOST.STATE / ('schema-approval-' + aid + '.used')))
        require(marker.get('request') == SOURCE_REQUEST and marker.get('plan_sha256') == SOURCE_PLAN_SHA
                and marker.get('phase') == phase and marker.get('approval_sha256') == entry.get('sha256'))
    return source, plan_raw, state, journal_raw


@contextmanager
def source_lock():
    import fcntl
    require(sys.platform == 'linux' and os.geteuid() == 0)
    T.HOST.protected(T.HOST.STATE, directory=True)
    lock = T.HOST.STATE / 'lock'; T.HOST.protected(lock)
    fd = os.open(lock, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as f:
        fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def continuation(raw, expected_sha, source):
    require(T.HOST.sha(raw) == expected_sha)
    plan = json.loads(raw)
    T.validate_plan(plan, plan.get('request'))  # Fresh real expiry, never patch the old plan.
    require(plan['request'] != SOURCE_REQUEST)
    require(set(plan) == set(source))
    require(all(plan[k] == source[k] for k in source if k not in ('request', 'expires_at')))
    require(not (T.HOST.STATE / plan['request']).exists()
            and not (T.PLANS / (plan['request'] + '.json')).exists())
    return plan


def authorize(raw, signature, plan, plan_sha, source_raw, commands, deadline):
    require(len(raw) <= 16384 and len(read(signature, 16384)) <= 16384)
    proof = json.loads(raw)
    expected = dict(format=1, action='reconcile-v18-db-verified', source_request=SOURCE_REQUEST,
                    source_plan_sha256=SOURCE_PLAN_SHA, source_journal_sha256=T.HOST.sha(source_raw),
                    target_request=plan['request'], target_plan_sha256=plan_sha)
    require(set(proof) == set(expected) | {'approval_id', 'issued_at', 'expires_at'})
    require(all(proof.get(k) == v for k, v in expected.items()))
    require(re.fullmatch('[0-9a-f]{32}', proof.get('approval_id', '')) is not None)
    now = int(time.time())
    require(type(proof['issued_at']) is int and type(proof['expires_at']) is int
            and proof['issued_at'] <= now and now + 1200 <= proof['expires_at'] <= plan['expires_at']
            and 0 < proof['expires_at'] - proof['issued_at'] <= 7200)
    key = read(T.APPROVAL_KEY, 16384); require(T.HOST.sha(key) == PUBLIC_SHA)
    commands.run(['/usr/bin/openssl', 'dgst', '-sha256', '-verify', str(T.APPROVAL_KEY),
                  '-signature', str(signature)], deadline, 10, data=raw)
    return proof


def exclusive(path, raw, mode=0o400):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, 'wb') as f:
        f.write(raw); f.flush(); os.fchmod(f.fileno(), mode); os.fsync(f.fileno())
    fd = os.open(Path(path).parent, os.O_RDONLY | os.O_DIRECTORY)
    try: os.fsync(fd)
    finally: os.close(fd)


def consume(proof, envelope):
    aid = proof['approval_id']
    require(not (T.HOST.STATE / ('schema-approval-' + aid + '.used')).exists())
    # Reserve the ORIGINAL namespace too: unchanged phase tools must reject this ID.
    exclusive(T.HOST.STATE / ('schema-approval-' + aid + '.used'), canonical(dict(
        request=proof['target_request'], phase=proof['action'], plan_sha256=proof['target_plan_sha256'],
        approval_sha256=T.HOST.sha(envelope))))
    exclusive(T.HOST.STATE / ('schema-reconcile-' + aid + '.used'), canonical(proof))


def probes(plan, state, commands, temporary, plan_sha):
    global STAGE
    class ReadOnlyEngine(T.Engine):
        def migration(self, mode):
            require(mode in {'check-fence', 'check-after', 'validate-app'})
            return super().migration(mode)
    engine = ReadOnlyEngine(plan, commands, temporary, plan_sha)
    engine.directory = T.HOST.STATE / SOURCE_REQUEST
    engine.release.directory = engine.directory
    engine.record = dict(state)
    checks = [('protected-inputs', engine.protected_inputs), ('maintenance-host', engine.stopped),
              ('alb-contract', engine.release.verify_target_contract)]
    for name, check in checks:
        STAGE = name; check()
    STAGE = 'rds-identity'
    identity = engine.aws('sts', 'get-caller-identity')
    require(identity.get('Account') == '494915081306')
    db = engine.aws('rds', 'describe-db-instances', '--db-instance-identifier', plan['rds']).get('DBInstances', [])
    require(len(db) == 1 and db[0].get('DBInstanceStatus') == 'available'
            and db[0].get('Endpoint', {}).get('Address') == plan['db_host']
            and db[0].get('DBInstanceArn') == 'arn:aws:rds:ap-northeast-2:494915081306:db:hobbytree-dev-private')
    STAGE = 'alb-excluded'
    target = engine.aws('elbv2', 'describe-target-health', '--target-group-arn', T.HOST.TARGET_GROUP,
                        '--targets', 'Id=' + T.HOST.INSTANCE + ',Port=8080').get('TargetHealthDescriptions', [])
    require(len(target) == 1 and target[0].get('TargetHealth', {}).get('State') == 'unused'
            and target[0]['TargetHealth'].get('Reason') == 'Target.NotRegistered')
    STAGE = 'v5-snapshot'
    require(engine.snapshot() == state.get('snapshot_arn'))
    for name, check in [('fence-before', engine.check_fence),
                        ('check-after', lambda: engine.migration('check-after')),
                        ('validate-app', lambda: engine.migration('validate-app')),
                        ('fence-after', engine.check_fence)]:
        STAGE = name; check()
    return dict(result='PASS', source_request=SOURCE_REQUEST, source_plan_sha256=SOURCE_PLAN_SHA,
                target_request=plan['request'], target_plan_sha256=plan_sha,
                snapshot_arn=state['snapshot_arn'], fence_sha256=state['fence_sha256'],
                checks=[x[0] for x in checks] + ['rds-identity', 'alb-excluded', 'v5-snapshot',
                                               'fence-before', 'check-after', 'validate-app', 'fence-after'])


def handoff(plan, plan_raw, state, source_raw, source_plan_raw, proof, envelope, signature, report):
    """A new journal owner, only after signed/live proof. Original request is immutable."""
    global STAGE
    STAGE = 'handoff-preconditions'
    T.validate_plan(plan, plan['request'])
    require(report.get('result') == 'PASS' and report.get('source_request') == SOURCE_REQUEST
            and report.get('source_plan_sha256') == SOURCE_PLAN_SHA
            and report.get('target_request') == plan['request']
            and report.get('target_plan_sha256') == T.HOST.sha(plan_raw)
            and report.get('fence_sha256') == state['fence_sha256']
            and report.get('snapshot_arn') == state['snapshot_arn'])
    require(read(T.HOST.STATE / 'active.json') == source_raw
            and read(T.HOST.STATE / SOURCE_REQUEST / 'transition.json') == source_raw
            and read(T.PLANS / (SOURCE_REQUEST + '.json')) == source_plan_raw)
    target = T.HOST.STATE / plan['request']; require(not target.exists())
    target.mkdir(mode=0o700)
    STAGE = 'handoff-artifacts'
    for name, mode in [('settings.before', 0o600), ('history.tsv', 0o444), ('database-policy.tsv', 0o444)]:
        raw = read(T.HOST.STATE / SOURCE_REQUEST / name)
        if name == 'settings.before': require(T.HOST.sha(raw) == state['settings_sha256'])
        elif name == 'history.tsv': require(raw == ('\n'.join(plan['history']) + '\n').encode())
        else: require(raw == ('\n'.join([plan['database_owner'], *sorted(plan['privileged_login_roles'])]) + '\n').encode())
        exclusive(target / name, raw, mode)
    audit = {'source-journal.json': source_raw, 'source-plan.json': source_plan_raw,
             'reconcile-approval.json': envelope, 'reconcile-approval.sig': signature,
             'assessment.json': canonical(report)}
    for name, raw in audit.items(): exclusive(target / name, raw)
    exclusive(T.PLANS / (plan['request'] + '.json'), plan_raw)
    record = dict(state)
    record.update(request=plan['request'], plan_sha256=T.HOST.sha(plan_raw), status='db-verified', terminal_status=False,
                  source_request=SOURCE_REQUEST, source_plan_sha256=SOURCE_PLAN_SHA,
                  source_journal_sha256=T.HOST.sha(source_raw), source_phase_approvals=state['phase_approvals'],
                  external_operator_acl_recovery_required=False,
                  phase_approvals={'reconcile-v18-db-verified': {'id': proof['approval_id'], 'sha256': T.HOST.sha(envelope)}},
                  reconciliation_files={name: T.HOST.sha(raw) for name, raw in audit.items()})
    STAGE = 'handoff-journals'
    exclusive(target / 'transition.json', canonical(record), 0o600)
    T.HOST.atomic_write(T.HOST.STATE / 'active.json', canonical(record))
    require(read(T.HOST.STATE / 'active.json') == read(target / 'transition.json'))
    require(read(T.HOST.STATE / SOURCE_REQUEST / 'transition.json') == source_raw)


def main():
    global STAGE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('inspect', 'assess', 'reconcile'))
    parser.add_argument('--plan', type=Path)
    parser.add_argument('--plan-sha256')
    parser.add_argument('--approval', type=Path)
    parser.add_argument('--signature', type=Path)
    args = parser.parse_args()
    try:
        load_legacy()
        with source_lock():
            source, source_plan_raw, state, source_raw = context()
            if args.command == 'inspect':
                print(json.dumps(dict(source_request=SOURCE_REQUEST, source_plan_sha256=SOURCE_PLAN_SHA,
                    source_journal_sha256=T.HOST.sha(source_raw), status=state['status'],
                    original_plan_expires_at=source['expires_at'], fence_sha256=state['fence_sha256'],
                    snapshot_arn=state.get('snapshot_arn'), containment_failures=state['containment_failures']), sort_keys=True))
                return 0
            plan_raw = read(args.plan, 131071)
            plan = continuation(plan_raw, args.plan_sha256, source)
            with tempfile.TemporaryDirectory(prefix='v18-reconcile-', dir='/run') as temporary:
                commands = T.HOST.Commands(temporary); proof = None
                deadline = min(time.monotonic() + 1200, time.monotonic() + plan['expires_at'] - time.time())
                if args.command == 'reconcile':
                    envelope = read(args.approval, 16384); signature = read(args.signature, 16384)
                    proof = authorize(envelope, args.signature, plan, args.plan_sha256, source_raw, commands, deadline)
                    STAGE = 'consume-approval'; consume(proof, envelope)
                report = probes(plan, state, commands, temporary, args.plan_sha256)
                report['operation'] = args.command
                if proof is not None:
                    require(int(time.time()) + 1200 <= proof['expires_at'])
                    handoff(plan, plan_raw, state, source_raw, source_plan_raw, proof, envelope, signature, report)
                print(json.dumps(dict(report, handoff_complete=proof is not None), sort_keys=True))
        return 0
    except BaseException:
        print('FAIL: stopped at ' + STAGE + '; preserve maintenance/artifacts and reconcile any uncertain handoff; no migration or image rollback.', file=sys.stderr)
        return 1


if __name__ == '__main__': sys.exit(main())
