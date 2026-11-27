"""Bind importer execution to the reviewed file hash; preview by default."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile


def verified_bytes(path, digest):
    if not re.fullmatch(r'[0-9a-f]{64}', digest):
        raise ValueError()
    path = Path(path)
    if path.resolve() != path.absolute():
        raise ValueError()
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ValueError()
        raw = stream.read(1048577)
    if len(raw) > 1048576 or hashlib.sha256(raw).hexdigest() != digest:
        raise ValueError()
    return raw


def main(argv=None):
    parser = argparse.ArgumentParser(description='Pinned-file AI import (preview by default)')
    parser.add_argument('--file', required=True, type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--source-id', required=True)
    parser.add_argument('--source-epoch', required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--approve-plan')
    args = parser.parse_args(argv)
    try:
        if args.approve_plan and not args.apply:
            raise ValueError()
        raw = verified_bytes(args.file, args.sha256)
        # The importer reads a private copy, eliminating hash-check/read races.
        with tempfile.TemporaryDirectory(prefix='item-import-') as directory:
            pinned = Path(directory) / 'export.json'
            pinned.write_bytes(raw)
            command = [sys.executable, '-m', 'item_recommender.cli', 'sync-material-export',
                       '--file', str(pinned), '--source-id', args.source_id,
                       '--source-epoch', args.source_epoch]
            if args.apply:
                command.append('--apply')
            if args.approve_plan:
                command += ['--approve-plan', args.approve_plan]
            result = subprocess.run(command, capture_output=True, text=True, timeout=90)
            report = json.loads(result.stdout)
            if not isinstance(report, dict) or 'status' not in report or result.returncode not in (0, 1, 2, 3):
                raise ValueError()
            # Structured importer reports only. Never forward stderr/tracebacks.
            print(json.dumps(report, ensure_ascii=False))
            return result.returncode
    except Exception:
        # A timeout may have committed. Retry ONLY this identical file to reconcile.
        print('{"status":"IMPORT_INCOMPLETE_RECONCILE_SAME_FILE"}')
        return 1


if __name__ == '__main__':
    sys.exit(main())
