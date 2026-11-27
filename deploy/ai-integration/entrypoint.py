"""Load only role-specific file secrets; never print upstream exceptions."""
import os
from pathlib import Path
import re
import stat
import sys


def secret(path, *, token=False, provider=False):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077
                or info.st_uid != os.geteuid()):
            raise ValueError()
        data = stream.read(4097)
    value = data.decode('ascii').rstrip('\n')
    pattern = (r'[!-~]{1,256}' if provider else
               r'[A-Za-z0-9_-]{64,128}' if token else r'[A-Za-z0-9_-]{32,128}')
    if not re.fullmatch(pattern, value):
        raise ValueError()
    return value


def environment(role, base, directory=Path('/run/secrets')):
    if role not in ('api', 'worker', 'sync'):
        raise ValueError()
    result = dict(base)
    # No inherited plaintext credentials or SQLite fallback in the production package.
    for name in ('SERVICE_TOKEN', 'ADPICK_API_KEY', 'PGPASSWORD', 'PGPASSFILE',
                 'PGSERVICE', 'PGSERVICEFILE', 'PGOPTIONS', 'CATALOG_DB_PATH'):
        result.pop(name, None)
    result.update(APP_ENV='production',
                  CATALOG_DATABASE_URL='postgresql://item_app@catalog-db:5432/hobbytree_items',
                  PGPASSWORD=secret(directory / 'item_db_password'),
                  SERVICE_TOKEN=secret(directory / 'item_service_token', token=True))
    if role == 'worker':
        if (base.get('ADPICK_STORAGE_APPROVED') != 'true'
                or base.get('ADPICK_FILTERING_APPROVED') != 'true'):
            raise ValueError()
        result['ADPICK_API_KEY'] = secret(directory / 'adpick_api_key', provider=True)
    return result


def main():
    try:
        role = sys.argv[1]
        env = environment(role, os.environ)
        if role == 'api' and len(sys.argv) == 2:
            command = ['python', '-m', 'uvicorn', 'item_recommender.main:app',
                       '--host', '0.0.0.0', '--port', '8001', '--no-access-log']
        elif role == 'worker' and len(sys.argv) == 2:
            command = ['python', '-m', 'item_recommender.cli', 'worker']
        elif role == 'sync':
            command = ['python', '/integration/import_export.py', *sys.argv[2:]]
        else:
            raise ValueError()
    except Exception:
        print('ITEM_SECRET_OR_ROLE_INVALID', file=sys.stderr)
        return 1
    os.execvpe(command[0], command, env)


if __name__ == '__main__':
    sys.exit(main())
