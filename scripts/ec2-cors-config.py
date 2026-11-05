"""Materialize one approved public setting in the existing read-only configtree."""

import os
from pathlib import Path
import re
import stat
import tempfile


PERSISTENT = Path("/etc/hobbytree/cors-origins")
KEY = "CORS_ALLOWED_ORIGINS"
LABEL = r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
ORIGIN = re.compile(rf"https://((?:{LABEL}\.)+{LABEL})(?::([1-9][0-9]{{0,4}}))?")


def origins(value):
    """Validate canonical, explicit HTTPS origins. Empty means deny cross-origin requests."""
    entries = value.split(",") if value else []
    if len(value) > 2048 or len(entries) > 10 or len(entries) != len(set(entries)):
        raise ValueError("Invalid CORS allowlist.")
    for entry in entries:
        match = ORIGIN.fullmatch(entry)
        if not match:
            raise ValueError("Only explicit HTTPS DNS origins are allowed.")
        host, port = match.groups()
        if len(host) > 253 or host.split(".")[-1].isdigit() or host.endswith(".localhost"):
            raise ValueError("Only explicit HTTPS DNS origins are allowed.")
        if port and (int(port) == 443 or int(port) > 65535):
            raise ValueError("Use a canonical HTTPS origin port.")
    return entries


def root_directories(directory):
    """Reject paths that can be replaced or written by an unprivileged process."""
    for path in (directory, *directory.parents):
        info = path.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise PermissionError("Unprotected CORS settings directory.")


def runtime_path(directory):
    """Accept only the existing locked-down EC2 configtree directory."""
    directory = Path(directory)
    if Path("/run/hobbytree") not in directory.parents or ".." in directory.parts:
        raise PermissionError("Invalid CORS configtree directory.")
    root_directories(directory.parent)
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or (info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)) != (10001, 10001, 0o500):
        raise PermissionError("Unexpected CORS configtree ownership or mode.")
    if {item.name for item in directory.iterdir()} - {"DB_USERNAME", "DB_PASSWORD", "JWT_SECRET", "REDIS_PASSWORD", KEY}:
        raise PermissionError("Unreviewed property in the CORS configtree.")
    return directory / KEY


def read(path, mode):
    """Read an optional root-owned regular file without following symbolic links."""
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        return None
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(stream.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or
                (info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)) != (0, 0, mode)):
            raise PermissionError("Unexpected CORS settings ownership or mode.")
        value = stream.read(2049)
    origins(value.decode("ascii"))
    return value


def persistent_value():
    """Load the persistent root-only settings when an installation has opted in."""
    if not PERSISTENT.exists() and not PERSISTENT.is_symlink():
        return None
    root_directories(PERSISTENT.parent)
    return read(PERSISTENT, 0o600)


def write(path, value, mode):
    """Atomically replace or remove a validated setting, then sync its directory."""
    if value is None:
        path.unlink(missing_ok=True)
    else:
        origins(value.decode("ascii"))
        descriptor, temporary = tempfile.mkstemp(prefix=".cors-", dir=path.parent)
        try:
            with os.fdopen(descriptor, "wb") as stream:
                os.fchmod(stream.fileno(), mode)
                stream.write(value)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, path)
        finally:
            Path(temporary).unlink(missing_ok=True)
    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def materialize(directory):
    """Restore the approved setting after the service creates its /run configtree."""
    path = runtime_path(directory)
    before = read(path, 0o444)
    desired = persistent_value()
    if before != desired:
        write(path, desired, 0o444)
