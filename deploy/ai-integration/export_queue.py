"""Manual, single-writer export spool. Never sends or applies a catalog remotely."""
import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('be_export', ROOT / 'scripts/export-item-materials.py')
export = importlib.util.module_from_spec(spec)
spec.loader.exec_module(export)


def durable_json(path, value):
    fd, name = tempfile.mkstemp(prefix='.state-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
        sync_dir(path.parent)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def sync_dir(path):
    fd = os.open(path, os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


@contextmanager
def locked(directory):
    directory = Path(directory).absolute()
    info = directory.lstat()
    if (directory.resolve() != directory or not stat.S_ISDIR(info.st_mode)
            or info.st_uid != os.geteuid() or info.st_mode & 0o077):
        raise ValueError()
    fd = os.open(directory / '.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
            raise ValueError()
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield directory
    finally:
        os.close(fd)


def read_json(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError()
    return json.loads(path.read_text())


def initialize(directory, source_id, epoch):
    export.validate_identity(source_id, epoch, 1)
    with locked(directory) as directory:
        if any(p.name != '.lock' for p in directory.iterdir()):
            raise ValueError()
        durable_json(directory / 'state.json', {'sourceId': source_id, 'sourceEpoch': epoch, 'last': 0})


def produce(directory, service, connector=None):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', service):
        raise ValueError()
    with locked(directory) as directory:
        state = read_json(directory / 'state.json')
        sequence = state['last'] + 1
        export.validate_identity(state['sourceId'], state['sourceEpoch'], sequence)
        # Reserve durably BEFORE opening any DB connection. Failures leave gaps.
        state['last'] = sequence
        durable_json(directory / 'state.json', state)
        run = directory / str(sequence)
        run.mkdir(mode=0o700)
        sync_dir(directory)
        receipt = {'sequence': sequence, 'status': 'RESERVED'}
        durable_json(run / 'receipt.json', receipt)
        try:
            if connector is None:
                import psycopg
                connector = lambda name: psycopg.connect(
                    service=name, sslmode='verify-full', autocommit=True, connect_timeout=5,
                    application_name='hobbytree-material-export',
                    options='-c default_transaction_read_only=on')
            with connector(service) as connection:
                generated, rows = export.read_snapshot(connection)
            raw = export.build_document(rows, generated, state['sourceId'], state['sourceEpoch'], sequence)
            export.publish_exclusive(run / 'export.json', raw)
            sync_dir(run)
            receipt.update(status='READY' if rows else 'HOLD_EMPTY',
                           sha256=hashlib.sha256(raw).hexdigest(), bytes=len(raw), materials=len(rows))
            durable_json(run / 'receipt.json', receipt)
            return receipt
        except Exception:
            durable_json(run / 'receipt.json', {**receipt, 'status': 'FAILED'})
            raise


def replay(directory, sequence):
    export.positive_integer(sequence)
    with locked(directory) as directory:
        receipt = read_json(directory / str(sequence) / 'receipt.json')
        if receipt['status'] != 'READY':
            raise ValueError()
        path = directory / str(sequence) / 'export.json'
        if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != receipt['sha256']:
            raise ValueError()
        return receipt


def main(argv=None):
    parser = argparse.ArgumentParser(description='Manual immutable material export spool')
    parser.add_argument('--directory', type=Path, required=True)
    commands = parser.add_subparsers(dest='action', required=True)
    init = commands.add_parser('init')
    init.add_argument('--source-id', required=True)
    init.add_argument('--source-epoch', required=True)
    create = commands.add_parser('export')
    create.add_argument('--service', required=True)
    resend = commands.add_parser('replay')
    resend.add_argument('--sequence', required=True, type=int)
    args = parser.parse_args(argv)
    try:
        if args.action == 'init':
            initialize(args.directory, args.source_id, args.source_epoch)
            result = {'status': 'INITIALIZED'}
        elif args.action == 'export':
            result = produce(args.directory, args.service)
        else:
            result = replay(args.directory, args.sequence)
        print(json.dumps(result))
        return 0
    except Exception:
        print('{"status":"EXPORT_SPOOL_FAILED_KEEP_EVIDENCE"}')
        return 1


if __name__ == '__main__':
    sys.exit(main())
