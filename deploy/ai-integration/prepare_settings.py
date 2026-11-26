"""Stage files locally only. Never install, reload, or start a service."""
import argparse
import importlib.util
import os
from pathlib import Path
import sys

from entrypoint import secret

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('ec2_validator', ROOT / 'scripts/validate-ec2-config.py')
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


def stage_stt(contents):
    validator.validate_ai_properties(contents)
    # Preserve every original non-STT line and token byte; normalize only LF ending.
    lines = [line for line in contents.splitlines()
             if not line.startswith(('coach.stt.enabled=', 'coach.stt.request-timeout='))]
    result = '\n'.join(lines) + '\ncoach.stt.enabled=false\ncoach.stt.request-timeout=45s\n'
    validator.validate_ai_properties(result)
    return result


def exclusive(path, raw, uid=None):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(raw)
        stream.flush()
        if uid is not None:
            os.fchown(stream.fileno(), uid, uid)
            os.fchmod(stream.fileno(), 0o400)
        os.fsync(stream.fileno())


def stage_items(source, destination):
    # Explicit local files only, no environment discovery or AWS/Secret API calls.
    values = {name: secret(source / name, token=name == 'item_service_token')
              for name in ('item_service_token', 'item_db_password', 'catalog_admin_password')}
    if values['item_db_password'] == values['catalog_admin_password']:
        raise ValueError()
    destination.mkdir(mode=0o700)  # Never reuse/overwrite an installed directory.
    for name, value in values.items():
        exclusive(destination / name, (value + '\n').encode(),
                  999 if name == 'catalog_admin_password' else 10001)
    exclusive(destination / 'item_db_init_password',
              (values['item_db_password'] + '\n').encode(), 999)


def main(argv=None):
    parser = argparse.ArgumentParser(description='Local staging only; no installation')
    parser.add_argument('kind', choices=['stt', 'items'])
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        for path in (args.source, args.output.parent):
            if path.resolve() != path.absolute():
                raise ValueError()
        if args.kind == 'stt':
            # Restrict local source to an owner-only regular file before reading.
            import stat
            info = args.source.lstat()
            if (not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077
                    or info.st_uid != os.geteuid() or info.st_size > 4096):
                raise ValueError()
            exclusive(args.output, stage_stt(args.source.read_text('ascii')).encode('ascii'))
        else:
            stage_items(args.source, args.output)
        print('STAGED_ONLY_NOT_INSTALLED')
        return 0
    except Exception:
        print('STAGING_FAILED_KEEP_EXISTING_FILES', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
