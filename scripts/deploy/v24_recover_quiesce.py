#!/usr/bin/env python3
"""Explicitly signed, read-only reconciliation of an interrupted V18 quiesce.

The original failed request remains immutable. This tool creates a new request
only after independently observing the stopped V18 runtime and unchanged DB.
It never starts an application, changes DB grants, or executes a migration.
"""
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import tempfile
import time

PUBLIC_KEY = Path('/etc/hobbytree/transitions/approval-public.pem')
PUBLIC_SHA = '08f5c0df6bfbfadc4ec2a96b81e0274bcf46cb3df7f02f39cdd16622804e22a2'


def digest(raw):
    """Bind exact approved file bytes."""
    return hashlib.sha256(raw).hexdigest()


def checked(path, limit=131072):
    """Reject symlinks and unprotected code before importing adjacent modules."""
    for item in (path, *path.parents):
        info = item.lstat()
        if info.st_uid != 0 or stat.S_ISLNK(info.st_mode) or info.st_mode & 0o022:
            raise ValueError('Unprotected recovery input')
    with path.open('rb') as stream:
        raw = stream.read(limit+1)
    if len(raw) > limit:
        raise ValueError('Oversized recovery input')
    return raw


def strict(raw):
    """Reject duplicate fields before any module is loaded."""
    def unique(pairs):
        """Keep one unambiguous value per key."""
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)


def modules(bundle, plan):
    """Import only protected protocol files with the newly approved hashes."""
    result = []
    for name in ('v24_protocol', 'v24_host', 'v24_transition'):
        path = bundle / (name + '.py')
        if digest(checked(path)) != plan['bundle'][path.name]:
            raise ValueError('Recovery dependency changed')
        spec = importlib.util.spec_from_file_location(name, path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[name] = module
        spec.loader.exec_module(module)
        result.append(module)
    return result


def authenticate(raw, signed, plan_raw, tool_raw):
    """Authenticate the dependency hashes before executing any adjacent code."""
    proof = strict(raw)
    if (proof.get('action') != 'hobbytree-v24-reconcile-quiesce'
            or proof.get('plan_sha256') != digest(plan_raw)
            or proof.get('recovery_sha256') != digest(tool_raw)):
        raise ValueError('Unbound recovery input')
    public = checked(PUBLIC_KEY, 16384)
    if digest(public) != PUBLIC_SHA:
        raise ValueError('Unexpected public key')
    with tempfile.TemporaryDirectory(prefix='v24-recovery-signature-', dir='/run') as scratch:
        key, signature = Path(scratch)/'public.pem', Path(scratch)/'approval.sig'
        key.write_bytes(public)
        signature.write_bytes(signed)
        result = subprocess.run(['openssl','dgst','-sha256','-verify',str(key),'-signature',str(signature)],
            input=raw,capture_output=True,timeout=10,
            env={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','OPENSSL_CONF':'/dev/null'})
        if result.returncode != 0:
            raise ValueError('Invalid recovery signature')


def approval(p, raw, plan, plan_hash, tool_hash, now=None):
    """Require a fresh recovery-specific signature scope, not a normal phase."""
    now = int(time.time()) if now is None else now
    proof = p.strict_json(raw)
    fixed = dict(format=1, action='hobbytree-v24-reconcile-quiesce', request=plan['request'],
                 plan_sha256=plan_hash, recovery_sha256=tool_hash)
    p.require(set(proof) == set(fixed) | {'source_request', 'source_plan_sha256',
        'source_state_sha256', 'approval_id', 'issued_at', 'expires_at'})
    p.require(type(proof['format']) is int and all(proof[k] == v for k, v in fixed.items()))
    p.require(all(type(proof[k]) is str and p.ID.fullmatch(proof[k])
                  for k in ('request', 'source_request', 'approval_id')))
    p.require(proof['source_request'] != proof['request'])
    p.require(all(type(proof[k]) is str and p.SHA.fullmatch(proof[k])
                  for k in ('source_plan_sha256', 'source_state_sha256')))
    p.require(type(proof['issued_at']) is int and type(proof['expires_at']) is int
              and proof['issued_at'] <= now and now + 300 <= proof['expires_at']
              and 0 < proof['expires_at'] - proof['issued_at'] <= 7200)
    return proof


def source(p, h, plan, proof):
    """Bind the exact failed source and reject any transition that reached DB work."""
    directory = h.STATE / ('v24-' + proof['source_request'])
    state_raw, plan_raw = p.read(directory/'state.json'), p.read(directory/'plan.json')
    p.require(p.sha(state_raw) == proof['source_state_sha256']
              and p.sha(plan_raw) == proof['source_plan_sha256'])
    p.require(not (directory/'update.pending.json').exists()
              and not (directory/'update.pending.json').is_symlink())
    state, old = p.strict_json(state_raw), p.validate_plan(p.strict_json(plan_raw))
    p.require(state['request'] == old['request'] == proof['source_request']
              and state['plan_sha256'] == proof['source_plan_sha256']
              and state['phase'] == 'failed' and state['status'] == 'v24-failed'
              and state['failed_phase'] == 'quiesce'
              and set(state['approvals']) == {'prepare', 'quiesce'})
    p.require(set(state['containment']) == {'database', 'exclude', 'disable', 'stop'}
              and all(type(v) is bool for v in state['containment'].values())
              and all(state['containment'][k] for k in ('database', 'disable', 'stop')))
    p.require(set(state['facts']) == {'settings_sha256', 'previous_container_id',
                                     'redis_id', 'redis_mounts', 'database_cleanup'})
    p.require(state['facts']['database_cleanup'] and all(
        x['mode'] == 'check-before' and x['stopped_or_absent'] is True
        for x in state['facts']['database_cleanup']))
    allowed = {'request', 'revision', 'candidate', 'candidate_image_id', 'bundle', 'snapshot'}
    p.require(all(plan[k] == old[k] for k in old if k not in allowed))
    p.require(plan['revision'] != old['revision'] and plan['candidate'] != old['candidate'])
    p.require(all(plan['bundle'][k] == old['bundle'][k] for k in p.ARTIFACTS if k != 'v24_host.py'))
    p.require(plan['bundle']['v24_host.py'] != old['bundle']['v24_host.py'])
    legacy, settings = p.read(directory/'legacy-active.before.json'), p.read(directory/'settings.before')
    p.require(p.sha(legacy) == plan['legacy_active_sha256']
              and p.sha(settings) == state['facts']['settings_sha256'])
    return directory, state, old, state_raw, plan_raw, legacy, settings


def observe(p, h, host, state, old, settings):
    """Observe an unchanged stopped runtime without invoking any mutation phase."""
    host.bundle_check()
    host.identity()
    host.fixed_host()
    host.runtime.verify_target_contract()
    host.stopped(state)
    p.require(p.read(host.release.ENV_FILE) == settings)
    p.require(not any(path.exists() or path.is_symlink() for path in (h.INSTALL, h.DROPIN, h.POINTER, h.TICKET)))
    host.run(['python3', str(h.ROOT/'scripts/validate-ec2-config.py'), '--env-file',
              str(host.release.ENV_FILE), '--check-secrets'])
    _, model = host.compose(host.runtime.previous, host.plan['request'])
    current = host.runtime.container('backend')
    host.runtime.backend_runtime(current)
    p.require(current['Config']['Image'] == host.runtime.previous)
    environment = dict(item.split('=', 1) for item in current['Config']['Env'])
    p.require(all(environment.get(k) == str(v) for k, v in model['environment'].items()))
    host.check_ai()
    host.image_check()
    p.require(host.runtime.extract_schema(host.runtime.previous, 'previous') == host.plan['before_schema'])
    p.require(host.runtime.extract_schema(host.runtime.image, 'candidate') == host.plan['after_schema'])
    p.require(p.sha((host.scratch/'candidate.jar').read_bytes()) == host.plan['candidate_jar_sha256'])
    snapshots = host.aws('rds', 'describe-db-snapshots', '--db-instance-identifier', host.plan['rds'])['DBSnapshots']
    p.require(not any(row['DBSnapshotIdentifier'] in (old['snapshot'], host.plan['snapshot']) for row in snapshots))
    return current['State']['FinishedAt']


def reconcile(p, h, t, host, journal, proof, raw, signed, plan_raw):
    """Create a new quiesced request; never rewrite the failed source directory."""
    directory, original, old, source_raw, old_plan_raw, legacy, settings = source(p, h, host.plan, proof)
    p.require(p.read(h.STATE/'active.json') == source_raw)
    p.require(not journal.directory.exists() and not journal.directory.is_symlink())
    t.signature(host, raw, signed)
    for phase in ('prepare', 'quiesce'):
        nonce = original['approvals'][phase]
        p.require(type(nonce) is str and p.ID.fullmatch(nonce))
        previous = p.read(directory/(phase+'-'+nonce+'.approval.json'))
        issued = p.strict_json(previous)['issued_at']
        evidence = p.validate_approval(previous, proof['source_plan_sha256'], old['request'], phase, now=issued)
        p.require(evidence['approval_id'] == nonce)
        t.signature(host, previous, p.read(directory/(phase+'-'+nonce+'.approval.sig')))
    stopped_at = observe(p, h, host, original, old, settings)
    p.require(time.time() + 300 <= proof['expires_at'])
    marker = h.STATE/('v24-approval-'+proof['approval_id']+'.used')
    p.create(marker, p.canonical(dict(request=host.plan['request'], phase='reconcile-quiesce',
        plan_sha256=journal.plan_hash, approval_sha256=p.sha(raw))))
    journal.directory.mkdir(mode=0o700)
    p.sync(h.STATE)
    state = dict(request=host.plan['request'], plan_sha256=journal.plan_hash, phase='new',
                 approvals={'reconcile-quiesce': proof['approval_id']},
                 facts={k: v for k, v in original['facts'].items() if k != 'database_cleanup'},
                 recovery=dict(source_request=old['request'], source_plan_sha256=proof['source_plan_sha256'],
                               source_state_sha256=proof['source_state_sha256'], approval_id=proof['approval_id']))
    try:
        for name, data in (('plan.json', plan_raw), ('settings.before', settings),
            ('legacy-active.before.json', legacy), ('recovery-source.state.json', source_raw),
            ('recovery-source.plan.json', old_plan_raw), ('recovery.approval.json', raw), ('recovery.approval.sig', signed)):
            p.create(journal.directory/name, data)
        journal.save(state, block_legacy=False)
        host.database('check-before', state)
        host.stopped(original)
        p.require(p.read(host.release.ENV_FILE) == settings)
        p.require(p.read(directory/'state.json') == source_raw and p.read(directory/'plan.json') == old_plan_raw
                  and p.read(h.STATE/'active.json') == source_raw)
        p.require(time.time() + 60 <= proof['expires_at'])
        state['facts']['stopped_at'] = stopped_at
        state['phase'] = 'quiesced'
        journal.save(state)
    except BaseException:
        # A partial pair write is left for inspection under the existing intent
        # rules. Never overwrite a possibly committed gate with a guessed state.
        if not (journal.directory/'update.pending.json').exists() and p.read(h.STATE/'active.json') == source_raw:
            state['phase'], state['failed_phase'] = 'failed', 'reconcile-quiesce'
            journal.save(state, block_legacy=False)
        raise
    p.require(p.read(directory/'state.json') == source_raw and p.read(directory/'plan.json') == old_plan_raw)
    return dict(result='PASS', request=host.plan['request'], source_request=old['request'],
                phase='quiesced', application_started=False, database_writes=False)


def main():
    """Verify exact inputs and serialize recovery with every normal deployment."""
    if os.geteuid() != 0 or len(sys.argv) != 4:
        raise ValueError('Linux root and plan/approval/signature paths required')
    plan_path, approval_path, signature_path = map(Path, sys.argv[1:])
    plan_raw = checked(plan_path, 65536)
    raw, signed = checked(approval_path, 16384), checked(signature_path, 16384)
    tool_raw = checked(Path(__file__).resolve())
    authenticate(raw, signed, plan_raw, tool_raw)
    p, h, t = modules(plan_path.parent, strict(plan_raw))
    plan = p.validate_plan(p.strict_json(plan_raw))
    proof = approval(p, raw, plan, p.sha(plan_raw), digest(tool_raw))
    journal = p.Journal(h.STATE, plan['request'], p.sha(plan_raw))
    with tempfile.TemporaryDirectory(prefix='v24-recover-', dir='/run') as scratch:
        host = h.Host(plan, plan_path.parent, journal, scratch, proof)
        with open(p.protected(h.STATE/'lock'), 'rb') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            print(json.dumps(reconcile(p, h, t, host, journal, proof, raw, signed, plan_raw)))


def interrupted(*unused):
    """Preserve evidence on a bounded operator interruption."""
    raise InterruptedError('Recovery interrupted')


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        main()
    except BaseException:
        print('Recovery incomplete: retain both requests; do not retry or start the application.', file=sys.stderr)
        sys.exit(1)
