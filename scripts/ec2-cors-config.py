"""Manage the one approved setting in the optional root-owned Compose env file."""

import os
from pathlib import Path
import re
import stat
import tempfile


PERSISTENT = Path("/etc/hobbytree/backend-cors.env")
KEY = "CORS_ALLOWED_ORIGINS"
PREFIX = (KEY + "=").encode("ascii")
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


def setting(value):
    """Serialize a validated allowlist; omit the file for the empty default."""
    origins(value)
    return PREFIX + value.encode("ascii") + b"\n" if value else None


def value_of(data):
    """Reject shell syntax, additional settings, and noncanonical bytes."""
    if data is None:
        return None
    if not data.startswith(PREFIX) or not data.endswith(b"\n"):
        raise ValueError("Invalid CORS environment file.")
    value = data[len(PREFIX):-1].decode("ascii")
    if not value or setting(value) != data:
        raise ValueError("Invalid CORS environment file.")
    return value


def persistent_value():
    """Read the optional root-only setting without following symbolic links."""
    root_directories(PERSISTENT.parent)
    try:
        descriptor = os.open(PERSISTENT, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        return None
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(stream.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or
                (info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)) != (0, 0, 0o600)):
            raise PermissionError("Unexpected CORS settings ownership or mode.")
        data = stream.read(len(PREFIX) + 2050)
    value_of(data)
    return data


def write(data):
    """Atomically replace or remove the root-only file, then sync its directory."""
    root_directories(PERSISTENT.parent)
    if data is None:
        PERSISTENT.unlink(missing_ok=True)
    else:
        value_of(data)
        descriptor, temporary = tempfile.mkstemp(prefix=".cors-", dir=PERSISTENT.parent)
        try:
            with os.fdopen(descriptor, "wb") as stream:
                os.fchmod(stream.fileno(), 0o600)
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, PERSISTENT)
        finally:
            Path(temporary).unlink(missing_ok=True)
    descriptor = os.open(PERSISTENT.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
