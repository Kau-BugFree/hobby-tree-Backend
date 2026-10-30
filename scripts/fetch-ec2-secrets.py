#!/usr/bin/env python3
"""Retrieve only the application DB secret on EC2; never print secret contents."""

import argparse
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tempfile


def require_linux_root():
    if os.name != "posix" or os.geteuid() != 0:
        raise PermissionError("Run on the Linux EC2 host using sudo")


def validate_secret_directory(value):
    directory = PurePosixPath(value)
    base = PurePosixPath("/run/hobbytree")
    if ".." in directory.parts or base not in directory.parents:
        raise ValueError("Secret directory must be a child of /run/hobbytree")


def read_credentials(secret_id, region, expected_username):
    if not expected_username or expected_username.lower() in {"postgres", "rdsadmin"}:
        raise ValueError("An explicit, non-administrator application username is required")
    result = subprocess.run(
        ["aws", "secretsmanager", "get-secret-value", "--secret-id", secret_id,
         "--region", region, "--version-stage", "AWSCURRENT", "--query", "SecretString",
         "--output", "text", "--no-cli-pager"],
        check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        encoding="utf-8", timeout=45,
    )
    secret = json.loads(result.stdout)
    if not isinstance(secret, dict):
        raise ValueError("SecretString must be a JSON object")
    username = secret.get("username")
    password = secret.get("password")
    if username != expected_username:
        raise ValueError("Secret username does not match the expected application account")
    if not isinstance(password, str) or not password or password != password.strip():
        raise ValueError("Secret password must be nonempty and have no surrounding whitespace")
    if any(character in username + password for character in ("\x00", "\r", "\n")):
        raise ValueError("Credentials must not contain NUL or line breaks")
    return {"DB_USERNAME": username, "DB_PASSWORD": password}


def write_credentials(directory, credentials):
    # File-backed Compose mounts retain host ownership; the runtime UID is 10001.
    require_linux_root()
    validate_secret_directory(directory)
    directory = Path(directory)
    if not directory.is_absolute() or ".." in directory.parts:
        raise ValueError("An absolute secret directory without parent traversal is required")
    for component in (directory, *directory.parents):
        if component.is_symlink():
            raise ValueError("Symlinks are not allowed in the secret directory path")
    os.umask(0o077)
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chown(directory, 0, 0)
    os.chmod(directory, 0o700)
    for name, value in credentials.items():
        temporary = None
        try:
            descriptor, temporary = tempfile.mkstemp(prefix=".secret-", dir=directory)
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as stream:
                stream.write(value)
                stream.flush()
                os.fsync(stream.fileno())
            os.chown(temporary, 10001, 10001)
            os.chmod(temporary, 0o400)
            os.replace(temporary, directory / name)
            temporary = None
        finally:
            if temporary is not None:
                os.unlink(temporary)
    os.chown(directory, 10001, 10001)
    os.chmod(directory, 0o500)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--secret-id", required=True)
    parser.add_argument("--expected-username", required=True)
    parser.add_argument("--region", default="ap-northeast-2")
    parser.add_argument("--output-dir", default="/run/hobbytree/secrets")
    arguments = parser.parse_args()
    try:
        require_linux_root()
        validate_secret_directory(arguments.output_dir)
        credentials = read_credentials(arguments.secret_id, arguments.region, arguments.expected_username)
        write_credentials(arguments.output_dir, credentials)
    except Exception:
        # Do not propagate AWS responses, JSON parser snippets, or credential values.
        print("Secret preparation failed. Check the app secret, account, IAM, network and Linux permissions.", file=sys.stderr)
        return 1
    print("Application DB secret files prepared; values were not logged.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
