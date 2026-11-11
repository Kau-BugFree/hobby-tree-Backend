#!/usr/bin/env python3
"""Reconcile a fully installed, fenced V24 configure failure under a fresh approval."""
import copy
import fcntl
import hashlib
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import tempfile
import time
import types

ACTION = 'hobbytree-v24-reconcile-configure'
PHASE = 'reconcile-configure'
BACKUP_TOOL_SHA = '4f35d8fad1750e42c4bef90a41fe04def79cac7594dec291b654d0578846690a'
SOURCE_PHASES = ('verify-fence', 'backup', 'migrate', 'configure')


def load_backup_tool():
    """Reuse the reviewed backup proof verifier from an exact, protected copy."""
    path = Path(__file__).resolve().with_name('v24_recover_backup.py')
    for item in (path, *path.parents):
        info = item.lstat()
        if info.st_uid != 0 or stat.S_ISLNK(info.st_mode) or info.st_mode & 0o022:
            raise ValueError('Unprotected historical verifier')
    with path.open('rb') as stream:
        raw = stream.read(131073)
    if hashlib.sha256(raw).hexdigest() != BACKUP_TOOL_SHA:
        raise ValueError('Historical verifier changed')
    module = types.ModuleType('v24_historical_backup')
    exec(compile(raw, str(path), 'exec'), module.__dict__)
    return module


def authenticate(backup, raw, signed, plan_raw, tool_raw):
    """Authenticate the new bundle hashes before importing its code."""
    proof = backup.strict(raw)
    if (proof.get('action') != ACTION or proof.get('plan_sha256') != backup.digest(plan_raw)
            or proof.get('recovery_sha256') != backup.digest(tool_raw)):
        raise ValueError('Unbound configure recovery')
    public = backup.checked(backup.PUBLIC_KEY, 16384)
    if backup.digest(public) != backup.PUBLIC_SHA:
        raise ValueError('Unexpected public key')
    with tempfile.TemporaryDirectory(prefix='v24-configure-signature-', dir='/run') as scratch:
        key, signature = Path(scratch)/'public.pem', Path(scratch)/'approval.sig'
        key.write_bytes(public)
        signature.write_bytes(signed)
        subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(key), '-signature', str(signature)],
                       input=raw, capture_output=True, check=True, timeout=10,
                       env={'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'OPENSSL_CONF': '/dev/null'})
    return proof


def validate_proof(p, proof, plan):
    """Bind a new request to immutable source hashes and a short-lived nonce."""
    p.require(set(proof) == {'format', 'action', 'request', 'plan_sha256', 'recovery_sha256',
                           'source_request', 'source_plan_sha256', 'source_state_sha256',
                           'approval_id', 'issued_at', 'expires_at'})
    p.require(type(proof['format']) is int and proof['format'] == 1
              and proof['action'] == ACTION and proof['request'] == plan['request'])
    p.require(all(type(proof[key]) is str and p.ID.fullmatch(proof[key])
                  for key in ('source_request', 'approval_id')))
    p.require(proof['source_request'] != plan['request'])
    p.require(all(type(proof[key]) is str and p.SHA.fullmatch(proof[key])
                  for key in ('source_plan_sha256', 'source_state_sha256')))
    p.require(type(proof['issued_at']) is int and type(proof['expires_at']) is int
              and proof['issued_at'] <= time.time() and time.time() + 300 <= proof['expires_at']
              and proof['expires_at'] - proof['issued_at'] <= 7200)


def read_source(p, h, plan, proof):
    """Accept only the active configure failure after a completed V24 migration."""
    directory = h.STATE/('v24-'+proof['source_request'])
    plan_raw, state_raw = p.read(directory/'plan.json'), p.read(directory/'state.json')
    p.require(p.sha(plan_raw) == proof['source_plan_sha256'] and p.sha(state_raw) == proof['source_state_sha256'])
    p.require(p.read(h.STATE/'active.json') == state_raw and not os.path.lexists(directory/'update.pending.json'))
    old, state = p.validate_plan(p.strict_json(plan_raw)), p.strict_json(state_raw)
    p.require(old['request'] == state['request'] == proof['source_request']
              and state['plan_sha256'] == proof['source_plan_sha256']
              and state['phase'] == 'failed' and state['status'] == 'v24-failed'
              and state['failed_phase'] == 'configure'
              and set(state['approvals']) == {'reconcile-backup', *SOURCE_PHASES})
    p.require(set(state['containment']) == {'database', 'exclude', 'disable', 'stop'}
              and all(value is True for value in state['containment'].values()))
    facts = state['facts']
    p.require(set(facts) == {'schema_version', 'settings_sha256', 'previous_container_id',
                            'redis_id', 'redis_mounts', 'database_cleanup', 'stopped_at',
                            'fence_sha256', 'fence_verified_at', 'snapshot_arn'}
              and facts['schema_version'] == 24 and p.SHA.fullmatch(facts['fence_sha256']))
    p.require(facts['database_cleanup'] and all(row['stopped_or_absent'] is True
              for row in facts['database_cleanup']))
    p.require(facts['snapshot_arn'] == f"arn:aws:rds:{old['region']}:{old['account']}:snapshot:{old['snapshot']}")
    p.require(all(plan[key] == old[key] for key in old if key not in ('request', 'snapshot', 'bundle')))
    p.require({name for name in old['bundle'] if old['bundle'][name] != plan['bundle'][name]} == {'v24_host.py'})
    return directory, old, state, plan_raw, state_raw


def verify_history(backup, p, h, t, host, directory, old, state):
    """Verify past signatures and consumed nonces without authorizing another phase."""
    raw = p.read(directory/'recovery.approval.json', 16384)
    proof = backup.approval(p, raw, old, state['plan_sha256'], BACKUP_TOOL_SHA,
                            now=p.strict_json(raw)['issued_at'])
    p.require(state['approvals']['reconcile-backup'] == proof['approval_id'])
    p.require(state['recovery'] == {key: proof[key] for key in
              ('source_request', 'source_plan_sha256', 'source_state_sha256', 'approval_id')})
    t.signature(host, raw, p.read(directory/'recovery.approval.sig', 16384))
    backup.consumed(p, h, old['request'], 'reconcile-backup', state['plan_sha256'], proof['approval_id'], raw)
    prior_dir, prior_state, prior_plan, prior_raw, prior_plan_raw, _, _ = backup.source(p, h, old, proof)
    p.require(p.read(directory/'recovery-source.state.json') == prior_raw
              and p.read(directory/'recovery-source.plan.json') == prior_plan_raw)
    backup.recovery_evidence(p, h, t, host, prior_dir, prior_plan, prior_state)
    for phase in ('verify-fence', 'backup'):
        backup.phase_evidence(p, h, t, host, prior_dir, prior_plan, prior_state, phase)
    for phase in SOURCE_PHASES:
        backup.phase_evidence(p, h, t, host, directory, old, state, phase)


def observe(p, h, host, directory, old, original):
    """Require the exact completed installation and the original available backup."""
    host.bundle_check()
    host.identity()
    previous = copy.copy(host)
    previous.plan = old
    previous.fixed_host(after=True)
    p.require(p.read(h.POINTER, 128) == (old['request']+'\n').encode() and not os.path.lexists(h.TICKET))
    settings = p.read(directory/'settings.before')
    legacy = p.read(directory/'legacy-active.before.json')
    p.require(p.sha(settings) == original['facts']['settings_sha256']
              and p.sha(legacy) == old['legacy_active_sha256'])
    for name, key in (('validate-ec2-config.py', 'old_validator_sha256'),
                      ('fetch-ec2-secrets.py', 'old_fetch_sha256'),
                      ('ec2-deployment-contract.json', 'legacy_contract_sha256')):
        p.require(p.sha(p.read(directory/(name+'.before'))) == old[key])
    expected_settings = host.release.image_in_env(settings, host.runtime.image)
    p.require(p.read(host.release.ENV_FILE) == expected_settings)
    host.runtime.verify_target_contract()
    host.stopped(original)
    host.verify_unit()
    host.check_ai()
    host.image_check()
    host.run(['python3', str(h.ROOT/'scripts/validate-ec2-config.py'), '--env-file',
              str(host.release.ENV_FILE), '--check-secrets'])
    p.require(previous.snapshot(original) == original['facts']['snapshot_arn'])
    return settings, legacy, expected_settings


def reconcile(backup, p, h, t, host, journal, proof, raw, signed, plan_raw):
    """Publish a fresh configured request without executing configure or migrating again."""
    validate_proof(p, proof, host.plan)
    directory, old, original, old_plan_raw, source_raw = read_source(p, h, host.plan, proof)
    p.require(not os.path.lexists(journal.directory))
    verify_history(backup, p, h, t, host, directory, old, original)
    settings, legacy, expected_settings = observe(p, h, host, directory, old, original)
    p.require(time.time() + 300 <= proof['expires_at'])
    nonce = proof['approval_id']
    p.create(h.STATE/('v24-approval-'+nonce+'.used'), p.canonical(dict(
        request=host.plan['request'], phase=PHASE, plan_sha256=journal.plan_hash, approval_sha256=p.sha(raw))))
    journal.directory.mkdir(mode=0o700)
    p.sync(h.STATE)
    facts = {key: value for key, value in original['facts'].items() if key != 'database_cleanup'}
    state = dict(request=host.plan['request'], plan_sha256=journal.plan_hash, phase='new',
                 approvals={PHASE: nonce}, facts=facts,
                 recovery={key: proof[key] for key in
                           ('source_request', 'source_plan_sha256', 'source_state_sha256', 'approval_id')})
    try:
        for name, data in (('plan.json', plan_raw), ('settings.before', settings),
                           ('legacy-active.before.json', legacy), ('recovery-source.state.json', source_raw),
                           ('recovery-source.plan.json', old_plan_raw), ('recovery.approval.json', raw),
                           ('recovery.approval.sig', signed)):
            p.create(journal.directory/name, data)
        journal.save(state, block_legacy=False)
        host.check_fence(state)
        host.database('check-after', state)
        host.database('validate-app', state)
        host.check_fence(state)
        observe(p, h, host, directory, old, original)
        p.require(p.read(directory/'state.json') == source_raw and p.read(directory/'plan.json') == old_plan_raw
                  and p.read(h.STATE/'active.json') == source_raw and time.time() + 60 <= proof['expires_at'])
        # Until the journal pair is committed, neither pointer can authorize a start.
        repaired = p.read(host.bundle/'v24_host.py')
        p.require(p.sha(repaired) == host.plan['bundle']['v24_host.py'])
        p.replace(h.INSTALL/'v24_host.py', repaired, 0o500)
        host.fixed_host(after=True)
        p.replace(h.POINTER, (host.plan['request']+'\n').encode())
        facts['candidate_settings_sha256'] = p.sha(expected_settings)
        state['phase'] = 'configured'
        journal.save(state)
    except BaseException:
        if not os.path.lexists(journal.directory/'update.pending.json') and p.read(h.STATE/'active.json') == source_raw:
            state.update(phase='failed', failed_phase=PHASE)
            journal.save(state, block_legacy=False)
        raise
    return dict(result='PASS', request=host.plan['request'], source_request=old['request'],
                phase='configured', application_started=False, database_writes=False)


def main():
    if os.geteuid() != 0 or len(sys.argv) != 4:
        raise ValueError('Linux root and plan/approval/signature paths required')
    backup = load_backup_tool()
    plan_path, approval_path, signature_path = map(Path, sys.argv[1:])
    plan_raw = backup.checked(plan_path, 65536)
    raw, signed = backup.checked(approval_path, 16384), backup.checked(signature_path, 16384)
    proof = authenticate(backup, raw, signed, plan_raw, backup.checked(Path(__file__).resolve()))
    p, h, t = backup.modules(plan_path.parent, backup.strict(plan_raw))
    plan = p.validate_plan(p.strict_json(plan_raw))
    validate_proof(p, proof, plan)
    journal = p.Journal(h.STATE, plan['request'], p.sha(plan_raw))
    with tempfile.TemporaryDirectory(prefix='v24-configure-recovery-', dir='/run') as scratch:
        host = h.Host(plan, plan_path.parent, journal, scratch, proof)
        with open(p.protected(h.STATE/'lock'), 'rb') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            print(p.canonical(reconcile(backup, p, h, t, host, journal, proof, raw, signed, plan_raw)).decode(), end='')


def interrupted(*unused):
    """Record an interruption through the same failure path."""
    raise InterruptedError('Recovery interrupted')


if __name__ == '__main__':
    try:
        signal.signal(signal.SIGTERM, interrupted)
        signal.signal(signal.SIGINT, interrupted)
        main()
    except BaseException as error:
        # Preserve the stage and source line without printing commands, environment or SQL.
        import traceback
        frames = traceback.extract_tb(error.__traceback__)
        frame = frames[-2] if len(frames) > 1 else frames[-1]
        print(f'Configure recovery incomplete: {type(error).__name__} at {Path(frame.filename).name}:{frame.lineno}',
              file=sys.stderr)
        sys.exit(1)
