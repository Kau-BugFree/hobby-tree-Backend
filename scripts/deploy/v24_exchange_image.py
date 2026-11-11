#!/usr/bin/env python3
"""Signed V24 image exchange. Preserve the database, Redis and prior journals."""
import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
import types

ACTION = 'hobbytree-v24-image-exchange'
PUBLIC_KEY = Path('/etc/hobbytree/transitions/approval-public.pem')
PUBLIC_SHA = '08f5c0df6bfbfadc4ec2a96b81e0274bcf46cb3df7f02f39cdd16622804e22a2'
REPLACED_TOOLS = ('v24_host.py', 'v24_transition.py')
PHASES = ('activate', 'publish')
ORIGIN = 'http://localhost:8081'
EXCHANGE_WINDOW = 1200
CLEANUP_RESERVE = 180


def digest(raw):
    """Hash the exact bytes covered by approval."""
    return hashlib.sha256(raw).hexdigest()


def protected_read(path, limit=65536):
    """Read a bounded regular file under root-protected directories."""
    if not path.is_absolute():
        raise ValueError('Absolute image exchange paths required')
    for item in (path, *path.parents):
        info = item.lstat()
        if info.st_uid != 0 or stat.S_ISLNK(info.st_mode) or info.st_mode & 0o022:
            raise ValueError('Unprotected image exchange input')
    if not path.is_file():
        raise ValueError('Image exchange input must be a regular file')
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    if not 0 < len(data) <= limit:
        raise ValueError('Invalid image exchange input size')
    return data


def signed_json(raw):
    """Reject ambiguous JSON before interpreting any executable dependency paths."""
    def unique(pairs):
        """Build an object with exactly one value per signed field."""
        values = {}
        for key, value in pairs:
            if key in values:
                raise ValueError('Duplicate signed field')
            values[key] = value
        return values
    return json.loads(raw, object_pairs_hook=unique)


def verify_signature(raw, signed):
    """Verify the manifest approval before importing staged Python code."""
    public = protected_read(PUBLIC_KEY, 16384)
    if digest(public) != PUBLIC_SHA:
        raise ValueError('Unexpected approval key')
    with tempfile.TemporaryDirectory(prefix='v24-exchange-signature-', dir='/run') as scratch:
        key, signature = Path(scratch)/'key.pem', Path(scratch)/'approval.sig'
        key.write_bytes(public)
        signature.write_bytes(signed)
        subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(key), '-signature', str(signature)],
                       input=raw, capture_output=True, check=True, timeout=10,
                       env={'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'OPENSSL_CONF': '/dev/null'})


def load_modules(bundle, plan):
    """Load the exact controller dependencies named in the signed plan."""
    modules = []
    for name in ('v24_protocol', 'v24_host', 'v24_transition'):
        path = bundle/(name+'.py')
        raw = protected_read(path)
        if digest(raw) != plan['bundle'][path.name]:
            raise ValueError('Image exchange tool dependency changed')
        module = types.ModuleType(name)
        module.__file__ = str(path)
        sys.modules[name] = module
        exec(compile(raw, str(path), 'exec'), module.__dict__)
        modules.append(module)
    return modules


def validate_approval(p, body, manifest_raw, tool_raw):
    """Bind a single direction to both plans and leave time for a complete exchange."""
    proof = p.strict_json(body)
    fixed = dict(format=1, action=ACTION, manifest_sha256=p.sha(manifest_raw), tool_sha256=p.sha(tool_raw))
    p.require(set(proof) == set(fixed) | {'direction', 'approval_id', 'issued_at', 'expires_at'})
    p.require(type(proof['format']) is int and all(proof[key] == value for key, value in fixed.items()))
    p.require(proof['direction'] in ('forward', 'rollback') and p.ID.fullmatch(proof['approval_id']))
    now = int(time.time())
    p.require(type(proof['issued_at']) is int and type(proof['expires_at']) is int
              and proof['issued_at'] <= now <= proof['expires_at'] - EXCHANGE_WINDOW - 30
              and proof['expires_at'] - proof['issued_at'] <= 7200)
    return proof


def validate_plans(p, manifest, source, forward, rollback):
    """Pin the same V24 history, configuration and two exact image identities."""
    p.require(set(manifest) == {'format', 'source_request', 'source_plan_sha256', 'source_state_sha256',
                               'forward_plan_sha256', 'rollback_plan_sha256', 'drain_seconds'})
    p.require(type(manifest['format']) is int and manifest['format'] == 1)
    p.require(type(manifest['drain_seconds']) is int and 0 <= manifest['drain_seconds'] <= 300)
    p.require(source['request'] == manifest['source_request']
              and len({plan['request'] for plan in (source, forward, rollback)}) == 3)
    movable = {'request', 'snapshot', 'previous', 'previous_image_id', 'candidate',
               'candidate_image_id', 'candidate_jar_sha256', 'revision', 'bundle'}
    for candidate in (forward, rollback):
        p.require(all(candidate[key] == source[key] for key in source if key not in movable))
        p.require(all(candidate['bundle'][key] == source['bundle'][key]
                      for key in source['bundle'] if key not in REPLACED_TOOLS))
    p.require(forward['bundle'] == rollback['bundle'])
    p.require((forward['previous'], forward['previous_image_id'])
              == (source['candidate'], source['candidate_image_id']))
    p.require((rollback['previous'], rollback['previous_image_id'])
              == (forward['candidate'], forward['candidate_image_id']))
    p.require(all(rollback[key] == source[key]
                  for key in ('candidate', 'candidate_image_id', 'candidate_jar_sha256', 'revision')))


def journal_bytes(p, h, request):
    """Read a stable request without recovering or editing its journal."""
    directory = h.STATE/('v24-'+request)
    p.require(not os.path.lexists(directory/'update.pending.json'))
    return directory, p.read(directory/'plan.json'), p.read(directory/'state.json')


def unit_properties(host, h):
    """Observe the unit status and owning systemd invocation."""
    raw = host.run(['systemctl', 'show', h.UNIT, '--property=ActiveState,UnitFileState,InvocationID'])
    return dict(line.split('=', 1) for line in raw.decode().splitlines())


def observed_backend(p, t, host, known):
    """Identify a present backend via its original request, nonce, image and ledger."""
    ids = host.run(['docker', 'ps', '-aq', '--no-trunc', '--filter',
                    'name=^/hobbytree-backend-1$']).decode().splitlines()
    if not ids:
        return None, None
    p.require(len(ids) == 1 and p.SHA.fullmatch(ids[0]))
    current = host.runtime.container('backend')
    p.require(current['Id'] == ids[0])
    labels = current['Config']['Labels']
    request, nonce = labels.get('hobbytree.v24.request'), labels.get('hobbytree.v24.nonce')
    p.require(request in known and type(nonce) is str and p.ID.fullmatch(nonce))
    owner = known[request]
    ledger = p.strict_json(p.read(owner.journal.directory/('backend-'+nonce+'.json')))
    p.require(ledger['approval_id'] == nonce and t.owned(owner, ledger)['Id'] == current['Id'])
    return current, ledger


def verify_image(p, host):
    """Check the pre-pulled image and compare its JAR and SQL before stopping service."""
    image = host.image_check()
    p.require(host.runtime.extract_schema(host.runtime.image, 'candidate') == host.plan['after_schema'])
    p.require(p.sha((host.scratch/'candidate.jar').read_bytes()) == host.plan['candidate_jar_sha256'])
    return image


def verify_installed(p, h, host, source, allow_partial):
    """Permit only source or proposed tool bytes during rollback from a partial install."""
    installed = dict(source['bundle'])
    for name in REPLACED_TOOLS:
        value = p.sha(p.read(h.INSTALL/name))
        accepted = {source['bundle'][name]}
        if allow_partial:
            accepted.add(host.plan['bundle'][name])
        p.require(value in accepted)
        installed[name] = value
    checker = copy.copy(host)
    checker.plan = dict(source, bundle=installed)
    checker.fixed_host(after=True)
    host.verify_unit()


def inspect_target(p, host, drain_seconds):
    """Require the reviewed target health settings and current drain delay."""
    host.runtime.verify_target_contract()
    attributes = host.aws('elbv2', 'describe-target-group-attributes',
                          '--target-group-arn', host.release.TARGET_GROUP)['Attributes']
    delay = [row['Value'] for row in attributes if row['Key'] == 'deregistration_delay.timeout_seconds']
    p.require(delay == [str(drain_seconds)])
    rows = host.aws('elbv2', 'describe-target-health',
                    '--target-group-arn', host.release.TARGET_GROUP)['TargetHealthDescriptions']
    p.require(len(rows) <= 1 and all(row['Target']['Id'] == host.plan['instance']
                                   and row['Target']['Port'] == 8080 for row in rows))
    return rows[0]['TargetHealth']['State'] if rows else 'unused'


def request_headers(p, host, container, path, origin=None, method=None, headers=None):
    """Read HTTP status and headers from the exact backend, without retaining the body."""
    args = ['docker', 'exec', container, 'curl', '--silent', '--show-error', '--max-time', '5',
            '--dump-header', '-', '--output', '/dev/null']
    if origin:
        args += ['-H', 'Origin: '+origin]
    if method:
        args += ['-X', 'OPTIONS', '-H', 'Access-Control-Request-Method: '+method,
                 '-H', 'Access-Control-Request-Headers: '+headers]
    lines = host.run(args+['http://127.0.0.1:8080'+path], 10).decode().splitlines()
    status = re.fullmatch(r'HTTP/\S+ ([0-9]{3})(?: .*|)', lines[0])
    p.require(status is not None)
    fields = {}
    for line in lines[1:]:
        if not line:
            break
        name, value = line.split(':', 1)
        fields.setdefault(name.lower(), []).append(value.strip())
    return int(status[1]), fields


def verify_runtime(p, host, direction, state):
    """Require health, JWT protection and the CORS contract before opening ALB traffic."""
    container = state['facts']['candidate_container_id']
    p.require(request_headers(p, host, container, '/actuator/health')[0] == 200)
    p.require(request_headers(p, host, container, '/api/v1/users/me')[0] == 401)
    if direction != 'forward':
        return
    for path, expected in (('/api/v1/onboarding/options', 200), ('/api/v1/users/me', 401)):
        code, headers = request_headers(p, host, container, path, ORIGIN)
        p.require(code == expected and headers.get('access-control-allow-origin') == [ORIGIN])
    public_origin = 'https://api.hobbytreeapp.com'
    code, headers = request_headers(p, host, container, '/api/v1/users/me', public_origin)
    p.require(code == 401 and headers.get('access-control-allow-origin') == [public_origin])
    for path, required in (('/api/v1/auth/social-login', 'content-type'),
                           ('/api/v1/recommendations', 'authorization,content-type,idempotency-key'),
                           ('/api/v1/coach/chat', 'authorization,content-type,idempotency-key')):
        code, headers = request_headers(p, host, container, path, ORIGIN, 'POST', required)
        allowed = {item.strip().lower() for value in headers.get('access-control-allow-headers', [])
                   for item in value.split(',')}
        methods = {item.strip() for value in headers.get('access-control-allow-methods', [])
                   for item in value.split(',')}
        p.require(code == 200 and headers.get('access-control-allow-origin') == [ORIGIN]
                  and 'POST' in methods and set(required.split(',')) <= allowed
                  and 'access-control-allow-credentials' not in headers)
    code, headers = request_headers(p, host, container, '/api/v1/auth/social-login',
                                    'http://localhost:8082', 'POST', 'content-type')
    p.require(code == 403 and 'access-control-allow-origin' not in headers)


def phase_approvals(p, h, t, host, deadline):
    """Check both guarded start and publication approvals before draining the live backend."""
    result = {}
    for phase in PHASES:
        raw = p.read(host.bundle/(phase+'.approval.json'), 16384)
        sig = p.read(host.bundle/(phase+'.approval.sig'), 16384)
        proof = p.validate_approval(raw, host.journal.plan_hash, host.plan['request'], phase)
        p.require(proof['expires_at'] >= deadline and
                  not os.path.lexists(h.STATE/('v24-approval-'+proof['approval_id']+'.used')))
        t.signature(host, raw, sig)
        result[phase] = (proof, raw, sig)
    return result


def ready_rollback(p, h, t, host, manifest_raw, tool_raw):
    """Require a complete, signed rollback bundle before starting the forward exchange."""
    host.bundle_check()
    body = p.read(host.bundle/'exchange.approval.json', 16384)
    verify_signature(body, p.read(host.bundle/'exchange.approval.sig', 16384))
    proof = validate_approval(p, body, manifest_raw, tool_raw)
    deadline = int(time.time())+2*(EXCHANGE_WINDOW+30)+CLEANUP_RESERVE
    nonce = proof['approval_id']
    p.require(proof['direction'] == 'rollback' and proof['expires_at'] >= deadline
              and not os.path.lexists(h.STATE/('v24-approval-'+nonce+'.used')))
    phases = phase_approvals(p, h, t, host, deadline)
    nonces = (nonce, *(item[0]['approval_id'] for item in phases.values()))
    expires_at = min(proof['expires_at'], *(item[0]['expires_at'] for item in phases.values()))
    return nonces, expires_at


def require_exchange_windows(p, proof, approvals, rollback_ready):
    """Preserve the execution and cleanup reserve at the point of downtime."""
    now = time.time()
    expires_at = min(proof['expires_at'], *(item[0]['expires_at'] for item in approvals.values()))
    p.require(expires_at >= now+EXCHANGE_WINDOW+30)
    if proof['direction'] == 'forward':
        p.require(rollback_ready is not None
                  and rollback_ready[1] >= now+2*(EXCHANGE_WINDOW+30)+CLEANUP_RESERVE)


def publish_exchange(p, h, host, state, approvals, direction):
    """Use the existing boot ticket and publication phases, then persist completion."""
    for phase in PHASES:
        if phase == 'publish':
            verify_runtime(p, host, direction, state)
        proof, raw, sig = approvals[phase]
        p.validate_approval(raw, host.journal.plan_hash, host.plan['request'], phase)
        host.journal.authorize_once(state, proof, raw, sig)
        host.proof = proof
        state['phase'] = phase+'-running'
        host.journal.save(state)
        facts = host.perform(phase, state)
        p.require(type(facts) is dict)
        state['facts'].update(facts)
        state['phase'] = p.PHASES[phase][1]
        host.journal.save(state)
    state['phase'] = 'complete'
    host.journal.save(state)


def fail_exchange(p, h, t, host, state, active_before, step, known):
    """Retain failure and containment without overwriting a pending pair update."""
    if os.path.lexists(host.journal.directory/'update.pending.json'):
        return
    failed_phase = state['phase'].removesuffix('-running') if state['phase'].endswith('-running') else step
    state.update(phase='failed', failed_phase=failed_phase)
    results = dict(database=False, exclude=False, stop=False)
    state['containment'] = results
    try:
        results['database'] = host.cleanup_database(state) is True
        host.deadline = time.monotonic()+90
        host.runtime.deadline = host.deadline
        target = host.runtime.target_state()
        if step not in ('preflight', 'drain') and target not in ('unused', 'draining'):
            host.target('deregister-targets')
            target = host.runtime.target_state()
        results['exclude'] = target == 'unused'
        if step not in ('preflight', 'drain') and results['exclude']:
            current, ledger = observed_backend(p, t, host, known)
            unit = unit_properties(host, h)
            if unit['ActiveState'] == 'active':
                p.require(current is not None and unit['InvocationID'] == ledger['invocation']
                          and p.read(h.POINTER, 128).decode().strip()
                          == current['Config']['Labels']['hobbytree.v24.request'])
                host.run(['systemctl', 'stop', h.UNIT], 60)
            stopped, _ = observed_backend(p, t, host, known)
            results['stop'] = (unit_properties(host, h)['ActiveState'] in ('inactive', 'failed')
                               and (stopped is None or not stopped['State']['Running']))
    except Exception:
        # The operator can complete draining under the separately signed rollback.
        pass
    finally:
        active = p.read(h.STATE/'active.json')
        p.require(active == active_before or p.strict_json(active)['request'] == host.plan['request'])
        host.journal.save(state, block_legacy=active != active_before)


def verified_forward_failure(p, h, t, host, manifest, manifest_raw, metadata, proof, failed, directory):
    """Verify that the recorded forward failure consumed a valid approval for this exchange."""
    raw = p.read(directory/'exchange.approval.json', 16384)
    approval = p.strict_json(raw)
    nonce = failed['approvals']['forward']
    p.require(type(nonce) is str and p.ID.fullmatch(nonce))
    p.require(failed['image_exchange'] == dict(metadata, direction='forward')
              and p.read(directory/'exchange.json') == manifest_raw
              and approval['action'] == ACTION and approval['direction'] == 'forward'
              and approval['approval_id'] == nonce and approval['manifest_sha256'] == p.sha(manifest_raw)
              and approval['tool_sha256'] == proof['tool_sha256'])
    t.signature(host, raw, p.read(directory/'exchange.approval.sig', 16384))
    marker = p.strict_json(p.read(h.STATE/('v24-approval-'+nonce+'.used')))
    p.require(marker == dict(request=failed['request'], phase='forward',
                             plan_sha256=manifest['forward_plan_sha256'], approval_sha256=p.sha(raw)))


def rollback_ticket(p, h, t, host, forward_host, failed):
    """Accept only the unconsumed boot ticket of the failed forward activation."""
    if not os.path.lexists(h.TICKET):
        return None
    raw = p.read(h.TICKET, 4096)
    ticket = p.strict_json(raw)
    nonce = failed['approvals'].get('activate')
    p.require(failed['failed_phase'] == 'activate' and type(nonce) is str and p.ID.fullmatch(nonce))
    fixed = dict(request=forward_host.plan['request'], plan_sha256=forward_host.journal.plan_hash,
                 phase='activate', approval_id=nonce, bundle=str(forward_host.bundle),
                 boot_id=Path('/proc/sys/kernel/random/boot_id').read_text().strip())
    p.require(set(ticket) == set(fixed) | {'verified_at'} and all(ticket[key] == value for key, value in fixed.items()))
    directory = forward_host.journal.directory
    body = p.read(directory/('activate-'+nonce+'.approval.json'), 16384)
    proof = p.validate_approval(body, forward_host.journal.plan_hash, forward_host.plan['request'],
                                'activate', now=p.strict_json(body)['issued_at'])
    p.require(proof['approval_id'] == nonce and type(ticket['verified_at']) is int
              and proof['issued_at'] <= ticket['verified_at'] <= min(proof['expires_at'], time.time()))
    t.signature(host, body, p.read(directory/('activate-'+nonce+'.approval.sig'), 16384))
    marker = p.strict_json(p.read(h.STATE/('v24-approval-'+nonce+'.used')))
    p.require(marker == dict(request=forward_host.plan['request'], phase='activate',
                             plan_sha256=forward_host.journal.plan_hash, approval_sha256=p.sha(body)))
    return raw


def exchange(p, h, t, host, source_host, forward_host, manifest, proof, raw, signed, plan_raw, manifest_raw,
             rollback_ready=None):
    """Execute an approved exchange or recover a failed exchange under the shared lock."""
    direction = proof['direction']
    source = source_host.plan
    source_dir, source_plan_raw, source_raw = journal_bytes(p, h, source['request'])
    original = p.strict_json(source_raw)
    p.require(p.sha(source_plan_raw) == manifest['source_plan_sha256'] and
              p.sha(source_raw) == manifest['source_state_sha256'] and original['phase'] == 'complete' and
              original['status'] == 'v24-complete' and original['request'] == source['request'] and
              original['plan_sha256'] == p.sha(source_plan_raw) and original['facts']['schema_version'] == 24)
    p.require(not os.path.lexists(host.journal.directory))
    forward = forward_host.plan
    manifest_hash = p.sha(manifest_raw)
    metadata = dict(direction=direction, manifest_sha256=manifest_hash, source_request=source['request'],
                    source_state_sha256=p.sha(source_raw), forward_request=forward['request'])
    active = p.read(h.STATE/'active.json')
    pointer = p.read(h.POINTER, 128).decode().strip()
    known = {source['request']: source_host}
    stale_ticket = None
    if direction == 'forward':
        p.require(active == source_raw and pointer == source['request'] and not os.path.lexists(h.TICKET))
        settings = p.read(host.release.ENV_FILE)
    else:
        failed_dir, failed_plan, failed_raw = journal_bytes(p, h, forward['request'])
        failed = p.strict_json(failed_raw)
        p.require(p.sha(failed_plan) == manifest['forward_plan_sha256'] and
                  failed['plan_sha256'] == p.sha(failed_plan) and failed['request'] == forward['request'] and
                  failed['phase'] == 'failed' and failed['status'] == 'v24-failed'
                  and failed['failed_phase'] != 'preflight')
        verified_forward_failure(p, h, t, host, manifest, manifest_raw, metadata, proof, failed, failed_dir)
        stale_ticket = rollback_ticket(p, h, t, host, forward_host, failed)
        p.require(active in (source_raw, failed_raw) and pointer in (source['request'], forward['request']))
        settings = p.read(failed_dir/'settings.before')
        known[forward['request']] = forward_host
    p.require(p.sha(settings) == original['facts']['candidate_settings_sha256'])
    current_settings = p.read(host.release.ENV_FILE)
    allowed_settings = {settings}
    if direction == 'rollback':
        allowed_settings.add(host.release.image_in_env(settings, forward_host.runtime.image))
    p.require(current_settings in allowed_settings)
    host.bundle_check()
    host.identity()
    verify_installed(p, h, host, source, allow_partial=direction == 'rollback')
    host.check_ai()
    host.same_redis(original)
    original_image = verify_image(p, source_host)
    if direction == 'forward':
        verify_image(p, host)
    target_state = inspect_target(p, host, manifest['drain_seconds'])
    current, ledger = observed_backend(p, t, host, known)
    unit = unit_properties(host, h)
    p.require(unit['UnitFileState'] == 'disabled' and unit['ActiveState'] in ('active', 'inactive', 'failed'))
    if current and current['State']['Running']:
        p.require(unit['ActiveState'] == 'active' and unit['InvocationID'] == ledger['invocation']
                  and current['Config']['Labels']['hobbytree.v24.request'] == pointer)
    if direction == 'forward':
        p.require(current is not None and current['Id'] == original['facts']['candidate_container_id']
                  and ledger['approval_id'] == original['facts']['active_approval_id']
                  and current['State']['Running'] and current['State']['Health']['Status'] == 'healthy'
                  and target_state == 'healthy')
        _, model = source_host.compose(source_host.runtime.image, ledger['approval_id'])
        source_host.runtime.backend_runtime(current)
        expected_env = dict(item.split('=', 1) for item in original_image['Config'].get('Env', []))
        expected_env.update({key: str(value) for key, value in model['environment'].items()})
        p.require(dict(item.split('=', 1) for item in current['Config']['Env']) == expected_env)
    approvals = phase_approvals(p, h, t, host, int(time.time())+EXCHANGE_WINDOW+30)
    nonce = proof['approval_id']
    nonces = [nonce, *(item[0]['approval_id'] for item in approvals.values())]
    if rollback_ready:
        nonces.extend(rollback_ready[0])
    p.require(len(set(nonces)) == len(nonces))
    require_exchange_windows(p, proof, approvals, rollback_ready)
    p.create(h.STATE/('v24-approval-'+nonce+'.used'), p.canonical(dict(
        request=host.plan['request'], phase=direction, plan_sha256=host.journal.plan_hash, approval_sha256=p.sha(raw))))
    directory = host.journal.directory
    directory.mkdir(mode=0o700)
    p.sync(h.STATE)
    facts = {key: value for key, value in original['facts'].items() if key not in
             ('active_approval_id', 'candidate_container_id', 'database_cleanup')}
    state = dict(request=host.plan['request'], plan_sha256=host.journal.plan_hash, phase='new',
                 approvals={direction: nonce}, facts=facts, image_exchange=metadata)
    for name, data in (('plan.json', plan_raw), ('source.plan.json', source_plan_raw),
                       ('source.state.json', source_raw), ('settings.before', settings),
                       ('exchange.json', manifest_raw), ('exchange.approval.json', raw),
                       ('exchange.approval.sig', signed)):
        p.create(directory/name, data)
    host.journal.save(state, block_legacy=False)
    step = 'preflight'
    try:
        require_exchange_windows(p, proof, approvals, rollback_ready)
        host.deadline = time.monotonic()+EXCHANGE_WINDOW
        host.runtime.deadline = host.deadline
        step = 'drain'
        if target_state != 'unused':
            if target_state != 'draining':
                host.target('deregister-targets')
            host.run(['aws', 'elbv2', 'wait', 'target-deregistered', '--region', host.plan['region'],
                      '--target-group-arn', host.release.TARGET_GROUP,
                      '--targets', 'Id='+host.plan['instance']+',Port=8080'], 420)
        host.excluded()
        step = 'stop'
        if unit['ActiveState'] == 'failed':
            host.run(['systemctl', 'reset-failed', h.UNIT], 30)
        elif unit['ActiveState'] != 'inactive':
            host.run(['systemctl', 'stop', h.UNIT], 60)
        stopped, _ = observed_backend(p, t, host, known)
        p.require((current is None and stopped is None) or
                  (current is not None and stopped is not None and stopped['Id'] == current['Id']
                   and not stopped['State']['Running']))
        facts['previous_container_id'] = stopped['Id'] if stopped else None
        if direction == 'rollback':
            facts['rollback_predecessor'] = (
                dict(Id=stopped['Id'], Image=stopped['Image'], reference=stopped['Config']['Image'])
                if stopped else None)
        host.stopped(state)
        step = 'install'
        p.require(p.read(source_dir/'state.json') == source_raw and p.read(h.STATE/'active.json') == active)
        host.bundle_check()
        if stale_ticket is not None:
            p.require(p.read(h.TICKET, 4096) == stale_ticket)
            p.create(directory/'forward-ticket.before.json', stale_ticket)
            h.TICKET.unlink()
            p.sync(h.TICKET.parent)
        else:
            p.require(not os.path.lexists(h.TICKET))
        for name in REPLACED_TOOLS:
            data = p.read(host.bundle/name)
            p.require(p.sha(data) == host.plan['bundle'][name])
            p.replace(h.INSTALL/name, data, 0o500)
        new_settings = host.release.image_in_env(settings, host.runtime.image) if direction == 'forward' else settings
        p.replace(host.release.ENV_FILE, new_settings)
        facts['candidate_settings_sha256'] = p.sha(new_settings)
        host.fixed_host(after=True)
        p.replace(h.POINTER, (host.plan['request']+'\n').encode())
        state['phase'] = 'configured'
        host.journal.save(state)
        step = 'verify-and-publish'
        publish_exchange(p, h, host, state, approvals, direction)
    except BaseException:
        fail_exchange(p, h, t, host, state, active, step, {**known, host.plan['request']: host})
        raise
    return dict(result='PASS', request=host.plan['request'], direction=direction, phase='complete')


def main():
    """Authenticate both plans and serialize the signed operation on the fixed host."""
    if os.geteuid() != 0 or len(sys.argv) != 4:
        raise ValueError('Linux root and manifest/approval/signature paths required')
    manifest_path, approval_path, signature_path = map(Path, sys.argv[1:])
    manifest_raw = protected_read(manifest_path)
    body, signed = protected_read(approval_path, 16384), protected_read(signature_path, 16384)
    tool_raw = protected_read(Path(__file__).resolve())
    verify_signature(body, signed)
    initial = signed_json(body)
    if (initial.get('manifest_sha256') != digest(manifest_raw) or initial.get('tool_sha256') != digest(tool_raw)
            or initial.get('action') != ACTION or initial.get('direction') not in ('forward', 'rollback')):
        raise ValueError('Unbound image exchange')
    manifest = signed_json(manifest_raw)
    root = manifest_path.parent
    plans = {}
    for direction in ('forward', 'rollback'):
        plan_raw = protected_read(root/direction/'plan.json')
        if digest(plan_raw) != manifest[direction+'_plan_sha256']:
            raise ValueError('Image exchange plan changed')
        plans[direction] = plan_raw
    direction = initial['direction']
    p, h, t = load_modules(root/direction, signed_json(plans[direction]))
    proof = validate_approval(p, body, manifest_raw, tool_raw)
    manifest = p.strict_json(manifest_raw)
    decoded = {key: p.validate_plan(p.strict_json(value)) for key, value in plans.items()}
    p.require(p.ID.fullmatch(manifest['source_request']))
    with open(p.protected(h.STATE/'lock'), 'rb') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        _, old_raw, _ = journal_bytes(p, h, manifest['source_request'])
        source = p.validate_plan(p.strict_json(old_raw))
        validate_plans(p, manifest, source, decoded['forward'], decoded['rollback'])
        with tempfile.TemporaryDirectory(prefix='v24-image-exchange-', dir='/run') as scratch:
            hosts = {}
            for name, plan, raw in (('source', source, old_raw),
                                    ('forward', decoded['forward'], plans['forward']),
                                    ('rollback', decoded['rollback'], plans['rollback'])):
                work = Path(scratch)/name
                work.mkdir()
                hosts[name] = h.Host(plan, root/name, p.Journal(h.STATE, plan['request'], p.sha(raw)), work, proof)
            rollback_ready = None
            if direction == 'forward':
                rollback_ready = ready_rollback(p, h, t, hosts['rollback'], manifest_raw, tool_raw)
            result = exchange(p, h, t, hosts[direction], hosts['source'], hosts['forward'], manifest, proof,
                              body, signed, plans[direction], manifest_raw, rollback_ready)
            print(p.canonical(result).decode(), end='')


def interrupted(*unused):
    """Preserve failure evidence on an operator interruption."""
    raise InterruptedError('Image exchange interrupted')


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        main()
    except BaseException as error:
        import traceback
        frames = traceback.extract_tb(error.__traceback__)
        frame = frames[-2] if len(frames) > 1 else frames[-1]
        print(f'Image exchange stopped: {type(error).__name__} at {Path(frame.filename).name}:{frame.lineno}',
              file=sys.stderr)
        sys.exit(1)
