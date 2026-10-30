#!/usr/bin/env python3
"""Create ephemeral CI TLS/DB fixtures; no cloud calls or credential output."""

import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys


def openssl(*arguments):
    subprocess.run(["openssl", *arguments], check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, timeout=30)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True)
    arguments = parser.parse_args()
    if os.name != "posix" or os.geteuid() != 0:
        raise RuntimeError("CI Linux root required")
    directory = Path(arguments.output_dir)
    if not directory.is_absolute() or directory.exists() or ".." in directory.parts:
        raise ValueError("A fresh absolute CI fixture directory is required")
    os.umask(0o077)
    directory.mkdir(mode=0o700, parents=True)
    postgres = directory / "postgres"
    postgres.mkdir(mode=0o700)
    app_password = secrets.token_hex(32)
    (postgres / "app-password").write_text(app_password, encoding="utf-8")
    (postgres / "admin-password").write_text(secrets.token_hex(32), encoding="utf-8")
    openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
            "-subj", "/CN=HobbyTree CI Root", "-addext", "basicConstraints=critical,CA:TRUE",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign",
            "-keyout", str(directory / "ca.key"), "-out", str(directory / "ca.pem"))
    openssl("req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=postgres",
            "-keyout", str(postgres / "server.key"), "-out", str(directory / "server.csr"))
    extensions = directory / "server.ext"
    extensions.write_text("subjectAltName=DNS:postgres\nbasicConstraints=critical,CA:FALSE\n"
                          "keyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n")
    openssl("x509", "-req", "-in", str(directory / "server.csr"), "-CA", str(directory / "ca.pem"),
            "-CAkey", str(directory / "ca.key"), "-CAcreateserial", "-days", "2",
            "-extfile", str(extensions), "-out", str(postgres / "server.crt"))
    (postgres / "pg_hba.conf").write_text("local all all trust\nhostssl all all all scram-sha-256\n"
                                         "hostnossl all all all reject\n")
    # The pinned Debian PostgreSQL image uses UID/GID 999. CI asserts this before startup.
    for file in postgres.iterdir():
        os.chown(file, 999, 999)
        os.chmod(file, 0o600 if file.name == "server.key" else 0o400)
    os.chown(postgres, 999, 999)
    os.chmod(postgres, 0o500)
    os.chmod(directory / "ca.pem", 0o644)
    (directory / "invalid-ca.pem").write_text("not a certificate\n")
    os.chmod(directory / "invalid-ca.pem", 0o644)
    secret_file = directory / "mock-secret.json"
    secret_file.write_text(json.dumps({"username": "ci_app", "password": app_password}))
    mock = directory / "bin"
    mock.mkdir()
    aws = mock / "aws"
    aws.write_text("#!/usr/bin/env python3\nimport os, pathlib, sys\n"
                   "assert sys.argv[1:3] == ['secretsmanager', 'get-secret-value']\n"
                   "sys.stdout.write(pathlib.Path(os.environ['CI_MOCK_SECRET_FILE']).read_text())\n")
    os.chmod(aws, 0o555)
    environment = dict(os.environ, PATH=str(mock) + os.pathsep + os.environ["PATH"],
                       CI_MOCK_SECRET_FILE=str(secret_file))
    helper = Path(__file__).resolve().parents[1] / "fetch-ec2-secrets.py"
    subprocess.run([sys.executable, str(helper), "--secret-id", "ci-mock", "--expected-username",
                    "ci_app", "--output-dir", "/run/hobbytree/ci/secrets"],
                   env=environment, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.chmod(directory, 0o755)  # Public CA is readable; private keys/credentials remain 0600/0400.
    print("CI TLS and DB fixtures prepared; credential values were not logged.")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("CI fixture preparation failed; details and credential values are suppressed.", file=sys.stderr)
        sys.exit(1)
