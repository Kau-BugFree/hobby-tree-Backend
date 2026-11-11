"""V18 -> V24 one-shot protocol. No cloud/DB calls and no implicit approval.

The host adapter supplies observations; transitions reject missing evidence. Old
journals are archived byte-for-byte before the shared legacy gate is blocked.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import time

SHA = re.compile(r'[0-9a-f]{64}')
ID = re.compile(r'[0-9a-f]{32}')
DIGEST = re.compile(r'sha256:[0-9a-f]{64}')
PHASES = {
    'prepare': ('new', 'prepared'),
    'quiesce': ('prepared', 'quiesced'),
    'verify-fence': ('quiesced', 'fenced'),
    'backup': ('fenced', 'backed-up'),
    'migrate': ('backed-up', 'migrated'),
    'configure': ('migrated', 'configured'),
    'activate': ('configured', 'healthy-excluded'),
    'publish': ('healthy-excluded', 'published'),
    'catalogue': ('published', 'complete'),
    'restart': ('complete', 'complete'),
}
HOST_FILES = {
    'validate-ec2-config.py': '/opt/hobbytree/scripts/validate-ec2-config.py',
    'fetch-ec2-secrets.py': '/opt/hobbytree/scripts/fetch-ec2-secrets.py',
    'ec2-deployment-contract.json': '/etc/hobbytree/deployment-contract.json',
}
TOOLS = {'v24_transition.py', 'v24_protocol.py', 'v24_host.py', '95-v24-guard.conf'}
ARTIFACTS = TOOLS | set(HOST_FILES) | {'history.tsv', 'database-policy.tsv', 'catalogue.sql'}


def require(condition, message='V24 transition invariant failed'):
    """Reject an unproven invariant before the next transition action."""
    if not condition:
        raise ValueError(message)


def sha(raw):
    """Hash exact bytes so approval and artifact identities cannot drift."""
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    """Serialize journal values deterministically for byte-for-byte gate comparison."""
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def strict_json(raw):
    """Reject duplicate JSON fields instead of silently accepting the last value."""
    def unique(pairs):
        """Build an unambiguous JSON object from parser-supplied pairs."""
        value = {}
        for key, item in pairs:
            require(key not in value, 'Duplicate JSON field')
            value[key] = item
        return value
    return json.loads(raw, object_pairs_hook=unique)


def validate_plan(plan):
    """Validate the exact reviewed target, artifacts, roles and 24-script contract."""
    fields = {'format', 'request', 'revision', 'candidate', 'candidate_image_id', 'candidate_jar_sha256',
              'previous', 'previous_image_id', 'before_schema', 'after_schema', 'db_host',
              'app_user', 'migration_user', 'database_owner', 'privileged_logins', 'snapshot',
              'legacy_active_sha256', 'legacy_guard_sha256', 'legacy_dropin_sha256',
              'legacy_contract_sha256', 'old_validator_sha256', 'old_fetch_sha256',
              'bundle', 'ai_properties_sha256', 'catalogue_source_sha256', 'history',
              'account', 'region', 'instance', 'rds', 'ai_private_ip'}
    require(set(plan) == fields and type(plan['format']) is int and plan['format'] == 1)
    require(ID.fullmatch(plan['request']) and re.fullmatch(r'[0-9a-f]{40}', plan['revision']))
    for key in ('candidate', 'candidate_image_id', 'previous', 'previous_image_id'):
        require(DIGEST.fullmatch(plan[key]))
    require(plan['candidate'] != plan['previous'] and plan['candidate_image_id'] != plan['previous_image_id'])
    for key in ('candidate_jar_sha256', 'before_schema', 'after_schema', 'legacy_active_sha256',
                'legacy_guard_sha256', 'legacy_dropin_sha256', 'legacy_contract_sha256',
                'old_validator_sha256', 'old_fetch_sha256', 'ai_properties_sha256', 'catalogue_source_sha256'):
        require(SHA.fullmatch(plan[key]))
    require(plan['before_schema'] != plan['after_schema'])
    require(set(plan['bundle']) == ARTIFACTS and all(SHA.fullmatch(v) for v in plan['bundle'].values()))
    require(re.fullmatch(r'[a-z0-9.-]+\.rds\.amazonaws\.com', plan['db_host']))
    require(re.fullmatch(r'[0-9]{12}', plan['account']) and plan['region'] == 'ap-northeast-2')
    require(re.fullmatch(r'i-[0-9a-f]{17}', plan['instance']) and plan['rds'] == 'hobbytree-dev-private')
    require(plan['snapshot'] == 'hobbytree-v24-' + plan['request'])
    import ipaddress
    ip = ipaddress.ip_address(plan['ai_private_ip'])
    require(ip.version == 4 and any(ip in ipaddress.ip_network(n) for n in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16')))
    names = [plan['app_user'], plan['migration_user'], plan['database_owner'], *plan['privileged_logins']]
    require(all(re.fullmatch(r'[a-z_][a-z0-9_]{0,62}', n) for n in names))
    require(plan['app_user'] != plan['migration_user'] and plan['database_owner'] in plan['privileged_logins'])
    require(len(set(plan['privileged_logins'])) == len(plan['privileged_logins'])
            and 1 <= len(plan['privileged_logins']) <= 20)
    require(plan['app_user'] not in plan['privileged_logins'] and plan['migration_user'] not in plan['privileged_logins'])
    require(len(plan['history']) == 24)
    for version, row in enumerate(plan['history'], 1):
        require(re.fullmatch(str(version) + r'\tV' + str(version) + r'__[A-Za-z0-9_]+\.sql\t-?[0-9]+', row))
    return plan


def validate_approval(body, plan_hash, request, phase, now=None, min_remaining=300):
    """Bind one phase to its plan and enforce admission or continuation expiry."""
    require(type(min_remaining) is int and min_remaining in (60, 300))
    now = int(time.time()) if now is None else now
    proof = strict_json(body)
    require(type(proof.get('format')) is int)
    expected = dict(format=1, action='hobbytree-v18-v24', request=request, phase=phase, plan_sha256=plan_hash)
    require(set(proof) == set(expected) | {'approval_id', 'issued_at', 'expires_at'})
    require(all(proof.get(k) == v for k,v in expected.items()) and phase in PHASES)
    require(ID.fullmatch(proof['approval_id']))
    require(type(proof['issued_at']) is int and type(proof['expires_at']) is int
            and proof['issued_at'] <= now and now + min_remaining <= proof['expires_at']
            and 0 < proof['expires_at']-proof['issued_at'] <= 7200)
    return proof


def protected(path, directory=False):
    """Require root ownership and no writable ancestors or symlinks."""
    path = Path(path)
    require(path.is_absolute())
    for part in (path, *path.parents):
        info = part.lstat()
        require(info.st_uid == 0 and not stat.S_ISLNK(info.st_mode) and not info.st_mode & 0o022)
    require(path.is_dir() if directory else path.is_file())
    return path


def read(path, limit=2_000_000):
    """Read a bounded, protected regular input without following untrusted paths."""
    path = protected(path)
    require(0 < path.stat().st_size <= limit)
    return path.read_bytes()


def sync(directory):
    """Make directory-entry changes durable before advancing the journal."""
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def create(path, raw, mode=0o600):
    """Create an exclusive durable file; never overwrite prior approval evidence."""
    path = Path(path)
    protected(path.parent, directory=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    sync(path.parent)


def replace(path, raw, mode=0o600):
    """Atomically replace a protected file and fsync its parent directory."""
    path = Path(path)
    protected(path.parent, directory=True)
    if path.exists() or path.is_symlink():
        protected(path)
    fd, temporary = tempfile.mkstemp(prefix='.v24-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            os.fchmod(stream.fileno(), mode)
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        sync(path.parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


class Journal:
    def __init__(self, root, request, plan_hash):
        """Locate a request journal without changing the legacy shared gate."""
        self.root, self.request, self.plan_hash = Path(root), request, plan_hash
        self.directory = self.root / ('v24-' + request)
        self.active = self.root / 'active.json'

    def load(self):
        """Reject request/plan mismatches and partial updates of the shared gate."""
        value = strict_json(read(self.directory / 'state.json'))
        require(value['request'] == self.request and value['plan_sha256'] == self.plan_hash)
        if value['phase'] not in ('new', 'prepared'):
            require(read(self.active) == canonical(value), 'Shared gate and V24 journal disagree')
        return value

    def save(self, state, block_legacy=True):
        """Persist local evidence and optionally block every legacy deployment path."""
        require(state['request'] == self.request and state['plan_sha256'] == self.plan_hash)
        # None of these states is a legacy TERMINAL status. Even success requires this guard.
        state['status'] = 'v24-' + state['phase']
        replace(self.directory / 'state.json', canonical(state))
        if block_legacy:
            replace(self.active, canonical(state))

    def begin(self, plan, plan_raw, signature=None):
        """Preserve the legacy gate and establish a new, non-overwriting request."""
        require(not self.directory.exists())
        require(sha(read(self.active)) == plan['legacy_active_sha256'])
        self.directory.mkdir(mode=0o700)
        sync(self.root)
        create(self.directory / 'legacy-active.before.json', read(self.active))
        create(self.directory / 'plan.json', plan_raw)
        state = dict(request=self.request, plan_sha256=self.plan_hash, phase='new', approvals={}, facts={})
        self.save(state, block_legacy=False)
        return state

    def authorize_once(self, state, proof, body, signature):
        """Consume a global nonce before recording a phase approval or doing work."""
        phase = proof['phase']
        require(state['phase'] == PHASES[phase][0], 'Out-of-order phase or unresolved failure')
        nonce = proof['approval_id']
        marker = self.root / ('v24-approval-' + nonce + '.used')
        create(marker, canonical(dict(request=self.request, phase=phase, plan_sha256=self.plan_hash, approval_sha256=sha(body))))
        create(self.directory / (phase + '-' + nonce + '.approval.json'), body)
        create(self.directory / (phase + '-' + nonce + '.approval.sig'), signature)
        state['approvals'][phase] = nonce


def execute_phase(journal, state, proof, adapter):
    """Advance one phase or retain its failure and containment evidence."""
    phase = proof['phase']
    require(state['phase'] == PHASES[phase][0])
    state['phase'] = phase + '-running'
    journal.save(state, block_legacy=phase != 'prepare')
    try:
        facts = adapter.perform(phase, state)
        require(type(facts) is dict)
        state['facts'].update(facts)
        state['phase'] = PHASES[phase][1]
        journal.save(state, block_legacy=phase != 'prepare')
    except BaseException:
        # Never rewind DB or image and never erase the original failure/replay marker.
        state['phase'] = 'failed'
        state['failed_phase'] = phase
        try:
            if phase not in ('prepare', 'catalogue'):
                state['containment'] = adapter.contain(state)
        finally:
            journal.save(state, block_legacy=phase != 'prepare')
        raise
    return state
