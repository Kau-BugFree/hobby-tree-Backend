"""Atomically create/recover a new host's service token without printing it."""

import os
from pathlib import Path
import secrets
import tempfile


def ensure_token(path, uid=10001):
    """Preserve valid tokens; atomically replace only missing/short tokens."""
    if path.is_symlink():
        raise ValueError('Service token must not be a symlink')
    valid = path.exists() and len(path.read_text('utf-8').strip()) >= 32
    if not valid:
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8',
                                             dir=path.parent, delete=False) as stream:
                temporary = Path(stream.name)
                stream.write(secrets.token_urlsafe(48))
                stream.flush()
                os.fsync(stream.fileno())
            os.chown(temporary, uid, uid)
            temporary.chmod(0o400)
            os.replace(temporary, path)
            directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    os.chown(path, uid, uid)
    path.chmod(0o400)


if __name__ == '__main__':
    ensure_token(Path('/opt/hobbytree-ai/secrets/coach_service_token'))
