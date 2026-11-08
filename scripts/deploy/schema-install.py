#!/usr/bin/env python3
"""Offline/root installation tool. No AWS, Docker, systemd or database commands."""
import argparse
from contextlib import contextmanager
try:
    import fcntl
except ImportError:
    fcntl = None
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

BASE = '/opt/hobbytree/scripts/deploy-schema'
CONFIG = '/etc/hobbytree/transitions'
SECRETS = '/run/hobbytree/migration-secrets'
STATE = '/var/lib/hobbytree/deployments'
BUNDLE_FILES = {
    'payload/ec2-release.py': ('scripts/deploy/ec2-release.py', BASE + '/ec2-release.py', '0500'),
    'payload/schema-transition.py': ('scripts/deploy/schema-transition.py', BASE + '/schema-transition.py', '0500'),
    'tools/schema-install.py': ('scripts/deploy/schema-install.py', BASE + '/schema-install.py', '0500'),
    'tools/schema-phase-approval.py': ('scripts/ci/schema-phase-approval.py', None, '0500'),
    'document/HobbyTree-Dev-SchemaTransition.json': ('deploy/ssm/HobbyTree-Dev-SchemaTransition.proposed.json', None, '0400'),
    'docs/installation.md': ('docs/v18-ssm-installation.md', None, '0400'),
    'docs/transition.md': ('docs/v5-latest-transition-runbook.md', None, '0400'),
}
DIRS = {BASE: (0, 0, 0o755), CONFIG: (0, 0, 0o700), CONFIG + '/approvals': (0, 0, 0o700), SECRETS: (10001, 10001, 0o500)}
ACK = 'install-isolated-schema-tools-without-service-or-aws-changes'


def require(ok):
    if not ok: raise ValueError('Installation verification failed')


def digest(raw): return hashlib.sha256(raw).hexdigest()


def public_key(path, expected):
    require(re.fullmatch('[0-9a-f]{64}', expected) is not None)
    p = Path(path); info = p.lstat()
    require(not p.is_symlink() and stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == 0 and not info.st_mode & 0o022)
    raw = p.read_bytes()
    require(len(raw) <= 16384 and digest(raw) == expected)
    require(b'PRIVATE KEY' not in raw)
    result = subprocess.run(['/usr/bin/openssl', 'pkey', '-pubin', '-pubcheck', '-noout', '-in', str(path)], capture_output=True, timeout=10)
    require(result.returncode == 0)
    return raw


def bundle(directory, expected):
    directory = Path(directory).absolute()
    require('..' not in directory.parts)
    require(re.fullmatch('[0-9a-f]{64}', expected) is not None)
    for parent in [directory, *directory.parents]:
        info = parent.lstat()
        require(stat.S_ISDIR(info.st_mode) and not parent.is_symlink() and info.st_uid == 0 and not info.st_mode & 0o022)
    manifest = directory / 'manifest.json'
    require(not manifest.is_symlink() and manifest.is_file() and manifest.stat().st_nlink == 1)
    require((manifest.stat().st_uid, manifest.stat().st_gid, stat.S_IMODE(manifest.stat().st_mode)) == (0, 0, 0o400))
    raw = manifest.read_bytes()
    require(digest(raw) == expected and len(raw) < 65536)
    m = json.loads(raw)
    require(set(m) == {'format', 'revision', 'files'} and m['format'] == 1)
    require(re.fullmatch('[0-9a-f]{40}', m['revision']) is not None and set(m['files']) == set(BUNDLE_FILES))
    for name, (source, destination, mode) in BUNDLE_FILES.items():
        e = m['files'][name]
        require(set(e) == {'source', 'destination', 'mode', 'uid', 'gid', 'sha256'})
        require((e['source'], e['destination'], e['mode'], e['uid'], e['gid']) == (source, destination, mode, 0, 0))
        p = directory / name
        for part in [directory, *p.relative_to(directory).parents]:
            if part == directory: require(not part.is_symlink())
            else: require(not (directory / part).is_symlink())
        require(not p.is_symlink() and p.is_file() and p.stat().st_nlink == 1)
        require((p.stat().st_uid, p.stat().st_gid, stat.S_IMODE(p.stat().st_mode)) == (0, 0, int(mode, 8)))
        require(digest(p.read_bytes()) == e['sha256'])
    require({p.relative_to(directory).as_posix() for p in directory.rglob('*') if p.is_file() or p.is_symlink()} == {'manifest.json', *BUNDLE_FILES})
    return directory, raw, m


class Host:
    # A filesystem root is injectable only by tests; production CLI has no root override.
    def __init__(self, root=Path('/')): self.root = Path(root)

    def path(self, absolute):
        require(absolute.startswith('/') and '..' not in Path(absolute).parts)
        return self.root / absolute.lstrip('/')

    def safe(self, absolute, directory=False, exact=None):
        p = self.path(absolute)
        for parent in [self.root, *[self.root / x for x in reversed(p.relative_to(self.root).parents) if str(x) != '.']]:
            info = parent.lstat()
            require(stat.S_ISDIR(info.st_mode) and not parent.is_symlink() and not info.st_mode & 0o022)
            expected_uid = 10001 if parent == self.path(SECRETS) else 0
            require(info.st_uid == expected_uid)
        info = p.lstat()
        require(not p.is_symlink() and (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)))
        if not directory: require(info.st_nlink == 1)
        if exact: require((info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)) == exact)
        else: require(info.st_uid == 0 and not info.st_mode & 0o022)
        return p

    def maybe(self, name, directory=False, exact=None):
        p = self.path(name)
        if p.exists() or p.is_symlink(): return self.safe(name, directory, exact)
        return None

    def parents(self):
        for name in ('/opt', '/opt/hobbytree', '/opt/hobbytree/scripts'):
            self.safe(name, True, (0, 0, 0o755))
        for name in ('/etc/hobbytree', '/run/hobbytree', STATE): self.safe(name, True)
        self.safe(STATE + '/active.json')

    @contextmanager
    def lock(self, terminal=True):
        self.parents()
        # An existing lock inode is mandatory; never replace or initialize a legacy journal.
        p = self.safe(STATE + '/lock')
        fd = os.open(p, os.O_RDWR | os.O_NOFOLLOW)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if terminal: require(json.loads(self.safe(STATE + '/active.json').read_bytes()).get('status') in {'succeeded', 'rolled-back', 'no-op'})
            yield
        finally: os.close(fd)

    def create_dir(self, name, meta):
        if self.maybe(name, True, meta): return
        p = self.path(name); p.mkdir(mode=0o700)
        os.chown(p, meta[0], meta[1]); os.chmod(p, meta[2])

    def check_file(self, name, raw, meta=(0, 0, 0o400), required=False):
        p = self.maybe(name, exact=meta)
        require(not required or p is not None)
        if p: require(p.read_bytes() == raw)

    def write(self, name, raw, meta=(0, 0, 0o400)):
        self.check_file(name, raw, meta)
        if self.path(name).exists(): return
        fd = os.open(self.path(name), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'wb') as out:
            out.write(raw); out.flush(); os.fchown(out.fileno(), meta[0], meta[1]); os.fchmod(out.fileno(), meta[2]); os.fsync(out.fileno())
        fd = os.open(self.path(name).parent, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(fd)
        finally: os.close(fd)

    def check(self, directory, raw, manifest, key, complete=False):
        self.parents()
        for name, meta in DIRS.items():
            present = self.maybe(name, True, meta)
            require(not complete or present is not None)
        if self.path(BASE).exists():
            require({p.name for p in self.path(BASE).iterdir()} <= {'ec2-release.py', 'schema-transition.py', 'schema-install.py'})
        for name, entry in manifest['files'].items():
            if entry['destination']:
                self.check_file(entry['destination'], (directory / name).read_bytes(), (0, 0, int(entry['mode'], 8)), complete)
        self.check_file(CONFIG + '/installation-manifest.json', raw, required=complete)
        self.check_file(CONFIG + '/approval-public.pem', key, required=complete)

    def install(self, directory, raw, manifest, key):
        self.check(directory, raw, manifest, key)
        for name, meta in DIRS.items(): self.create_dir(name, meta)
        for name, entry in manifest['files'].items():
            if entry['destination']: self.write(entry['destination'], (directory / name).read_bytes(), (0, 0, int(entry['mode'], 8)))
        self.write(CONFIG + '/installation-manifest.json', raw)
        self.write(CONFIG + '/approval-public.pem', key)
        self.check(directory, raw, manifest, key, complete=True)

    def credentials(self, username, password):
        require(username.strip() == b'hobbytree_migration' and len(username) <= 128)
        require(0 < len(password.strip()) <= 4096 and b'\x00' not in password)
        require(b'\n' not in password.strip() and b'\r' not in password.strip())
        self.safe(SECRETS, True, DIRS[SECRETS])
        for name, value in [('DB_USERNAME', username), ('DB_PASSWORD', password)]:
            self.check_file(SECRETS + '/' + name, value, (10001, 10001, 0o400))
        for name, value in [('DB_USERNAME', username), ('DB_PASSWORD', password)]:
            self.write(SECRETS + '/' + name, value, (10001, 10001, 0o400))

    def verify_credentials(self):
        self.safe(SECRETS, True, DIRS[SECRETS])
        u = self.safe(SECRETS + '/DB_USERNAME', exact=(10001, 10001, 0o400)).read_bytes()
        p = self.safe(SECRETS + '/DB_PASSWORD', exact=(10001, 10001, 0o400)).read_bytes()
        require(u.strip() == b'hobbytree_migration' and 0 < len(p.strip()) <= 4096 and b'\x00' not in p and b'\n' not in p.strip() and b'\r' not in p.strip())


def transition(directory):
    import importlib.util
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location('installed_transition', directory / 'payload/schema-transition.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def private_input(path):
    p = Path(path)
    info = p.lstat()
    require(not p.is_symlink() and stat.S_ISREG(info.st_mode) and info.st_nlink == 1)
    require((info.st_uid == 0 and stat.S_IMODE(info.st_mode) == 0o600)
            or (info.st_uid == 10001 and stat.S_IMODE(info.st_mode) == 0o400))
    require(info.st_size <= 4096)
    return p.read_bytes()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('preflight', 'install', 'verify', 'credentials', 'stage-plan', 'stage-approval'))
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--manifest-sha256', required=True)
    parser.add_argument('--public-key', type=Path, required=True)
    parser.add_argument('--public-key-sha256', required=True)
    parser.add_argument('--ack')
    parser.add_argument('--username-file', type=Path)
    parser.add_argument('--password-file', type=Path)
    parser.add_argument('--input', type=Path)
    parser.add_argument('--signature', type=Path)
    args = parser.parse_args()
    try:
        require(sys.platform == 'linux' and os.geteuid() == 0)
        directory, raw, manifest = bundle(args.bundle, args.manifest_sha256)
        key = public_key(args.public_key, args.public_key_sha256)
        host = Host()
        with host.lock(terminal=args.command not in {'stage-approval', 'verify'}):
            if args.command == 'preflight': host.check(directory, raw, manifest, key)
            elif args.command == 'install':
                require(args.ack == ACK); host.install(directory, raw, manifest, key)
            else:
                host.check(directory, raw, manifest, key, complete=True)
                if args.command == 'credentials':
                    require(args.ack == 'install-migration-credentials-only')
                    host.credentials(private_input(args.username_file), private_input(args.password_file))
                elif args.command == 'verify': host.verify_credentials()
                elif args.command == 'stage-plan':
                    require(args.ack == 'install-reviewed-transition-plan')
                    require(args.input.stat().st_size < 131072 and not args.input.is_symlink())
                    plan_raw = args.input.read_bytes(); plan = json.loads(plan_raw)
                    require(re.fullmatch('[0-9]{1,20}-[0-9]{1,5}', plan['request']) is not None)
                    tool = transition(directory); tool.validate_plan(plan, plan['request'])
                    require(plan['revision'] == manifest['revision'] and plan['host_helper_sha256'] == manifest['files']['payload/ec2-release.py']['sha256'])
                    host.write(CONFIG + '/' + plan['request'] + '.json', plan_raw)
                elif args.command == 'stage-approval':
                    require(args.ack == 'install-offline-signed-phase-approval')
                    require(args.input.stat().st_size <= 16384 and args.signature.stat().st_size <= 16384)
                    require(not args.input.is_symlink() and not args.signature.is_symlink())
                    body_raw = args.input.read_bytes(); signature_raw = args.signature.read_bytes()
                    proof = json.loads(body_raw); approval = proof['approval_id']
                    require(re.fullmatch('[0-9a-f]{32}', approval) is not None)
                    tool = transition(directory)
                    require(re.fullmatch('[0-9]{1,20}-[0-9]{1,5}', proof['request']) is not None and proof['phase'] in tool.APPROVALS)
                    marker = host.path(STATE + '/schema-approval-' + approval + '.used')
                    require(not marker.exists() and not marker.is_symlink())
                    plan_raw = host.safe(CONFIG + '/' + proof['request'] + '.json').read_bytes()
                    require(digest(plan_raw) == proof['plan_sha256'])
                    plan = tool.validate_plan(json.loads(plan_raw), proof['request'])
                    require(plan['revision'] == manifest['revision'])
                    state = json.loads(host.safe(STATE + '/active.json').read_bytes())
                    if proof['phase'] == 'quiesce': require(state.get('status') in tool.HOST.TERMINAL)
                    else: require(state.get('request') == proof['request'] and state.get('plan_sha256') == proof['plan_sha256'] and state.get('status') == tool.EXPECTED_STATE[proof['phase']])
                    # Use the same signature/context/expiry verifier as the execution path.
                    from types import SimpleNamespace
                    import tempfile, time
                    with tempfile.TemporaryDirectory(prefix='approval-check-', dir='/run') as tmp:
                        p = Path(tmp); os.chmod(p, 0o700)
                        (p / (approval + '.json')).write_bytes(body_raw); (p / (approval + '.sig')).write_bytes(signature_raw)
                        tool.PHASE_APPROVALS = p; tool.APPROVAL_KEY = host.path(CONFIG + '/approval-public.pem')
                        tool.authorize_phase(SimpleNamespace(approval_id=approval,request=proof['request'],phase=proof['phase'],plan_sha256=proof['plan_sha256']),tool.HOST.Commands(tmp),time.monotonic()+30)
                    require(proof['phase'] in tool.APPROVALS)
                    host.check_file(CONFIG + '/approvals/' + approval + '.json', body_raw)
                    host.check_file(CONFIG + '/approvals/' + approval + '.sig', signature_raw)
                    host.write(CONFIG + '/approvals/' + approval + '.json', body_raw)
                    host.write(CONFIG + '/approvals/' + approval + '.sig', signature_raw)
        print('PASS: ' + args.command + '; no service or AWS actions performed.')
        return 0
    except BaseException:
        print('FAIL: installation stopped; preserve existing files and journals; reconcile partial new files before retry.', file=sys.stderr)
        return 1


if __name__ == '__main__': sys.exit(main())
