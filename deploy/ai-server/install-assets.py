"""Install the approved, hash-pinned model/index allowlist on a NEW AI host.

Uses the host AWS CLI role. Never imports session state or overwrites live files.
An interrupted download is safely resumed by verifying already completed files.
"""

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess

ROOT = Path('/opt/hobbytree-ai')
BUCKET = 'hobbytree-ai-assets-494915081306-apne2'
PREFIX = 'releases/9364bface2638fb62c54edef6782ea58d5d73df9/'
MANIFEST_SHA = '0c0443db5beaf2b7a3fc247e4088d99757974a7629d6d4a290b0648b20e58eba'


def digest(path):
    """Hash a file without loading multi-gigabyte weights into memory."""
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def destination(name):
    """Reject traversal, non-allowlisted roots and session databases."""
    path = PurePosixPath(name)
    if (path.is_absolute() or '..' in path.parts or '\\' in name
            or str(path) != name):
        raise ValueError('Unsafe asset path')
    if name.startswith('models/'):
        target = ROOT / name
    elif name.startswith('backend/var/index/'):
        target = ROOT / 'state/index' / name.removeprefix('backend/var/index/')
    else:
        raise ValueError('Asset is outside model/index allowlist')
    for parent in (target, *target.parents):
        if parent.is_symlink():
            raise ValueError('Symlink in destination')
    return target


def main():
    """Verify each downloaded object before making it available to the service."""
    manifest = Path(__file__).with_name('assets-manifest.json')
    if digest(manifest) != MANIFEST_SHA:
        raise ValueError('Unexpected manifest')
    if (ROOT / 'state/coach.sqlite').exists():
        raise ValueError('Existing conversation state: use a separately reviewed update')
    entries = json.loads(manifest.read_text('utf-8-sig'))
    if len(entries) != 34 or sum(x['Bytes'] for x in entries) != 7852052666:
        raise ValueError('Unexpected asset count/size')
    seen = set()
    for item in entries:
        target = destination(item['Path'])
        if target in seen or not re.fullmatch('[a-f0-9]{64}', item['Sha256']):
            raise ValueError('Invalid manifest entry')
        seen.add(target)
    for item in entries:
        target = destination(item['Path'])
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            if target.stat().st_size != item['Bytes'] or digest(target) != item['Sha256']:
                raise ValueError('Existing asset differs: ' + item['Path'])
        else:
            partial = target.with_name(target.name + '.download')
            if partial.is_symlink():
                raise ValueError('Symlink download target')
            subprocess.run(['aws', 's3api', 'get-object', '--bucket', BUCKET,
                            '--key', PREFIX + 'assets/' + item['Path'],
                            '--expected-bucket-owner', '494915081306',
                            '--region', 'ap-northeast-2', str(partial)],
                           check=True, stdout=subprocess.DEVNULL)
            if partial.stat().st_size != item['Bytes'] or digest(partial) != item['Sha256']:
                raise ValueError('Downloaded asset failed verification: ' + item['Path'])
            partial.replace(target)
        writable = item['Path'].startswith('backend/')
        os.chown(target, 10001 if writable else 0, 10001 if writable else 0)
        target.chmod(0o600 if writable else 0o444)
        print('Verified ' + item['Path'], flush=True)
    for folder, uid, mode in [('state', 10001, 0o700), ('models', 0, 0o755)]:
        for parent, directories, _files in os.walk(ROOT / folder):
            os.chown(parent, uid, uid)
            os.chmod(parent, mode)
    print('Verified 34 approved assets; no session data imported')


if __name__ == '__main__':
    main()
