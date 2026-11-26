"""Export only reviewed files from the pinned AI checkout (no Git credentials)."""
import hashlib
import json
from pathlib import Path
import sys


def prepare(source, destination):
    lock = json.loads(Path(__file__).with_name('source-lock.json').read_text())
    source = Path(source) / 'src/item-recommender'
    destination = Path(destination)
    if destination.exists():
        raise ValueError()
    verified = {}
    for entry in lock['files']:
        name, expected = entry['path'], entry['sha256']
        path = source / name
        if path.is_symlink() or not path.is_file() or path.resolve() != path.absolute():
            raise ValueError()
        raw = path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != expected:
            raise ValueError()
        verified[name] = raw
    destination.mkdir(parents=True)
    for name, raw in verified.items():
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)


if __name__ == '__main__':
    try:
        prepare(*sys.argv[1:])
    except Exception:
        raise SystemExit('PINNED_ITEM_SOURCE_INVALID') from None
