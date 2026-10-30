#!/usr/bin/env python3
"""Fail closed on insecure EC2 JDBC/TLS settings; never print configuration values."""

import argparse
import base64
import ipaddress
import stat
import os
from pathlib import Path
import re
import shlex
import ssl
import sys
import time
from urllib.parse import parse_qsl, urlsplit

CONTAINER_CA = "/etc/hobbytree/certs/rds-ca.pem"


def validate_url(value):
    if not isinstance(value, str) or not value.startswith("jdbc:postgresql://"):
        raise ValueError("PostgreSQL JDBC URL required")
    if any(character.isspace() or ord(character) < 32 for character in value):
        raise ValueError("Whitespace or control character in URL")
    parsed = urlsplit(value[5:])
    if not parsed.hostname or parsed.username is not None or parsed.fragment:
        raise ValueError("Invalid endpoint or credentials in URL")
    if parsed.port is not None and not 1 <= parsed.port <= 65535:
        raise ValueError("Invalid port")
    if not re.fullmatch(r"/[A-Za-z0-9_.-]+", parsed.path):
        raise ValueError("Exactly one database name required")
    if re.search(r"%(?![0-9A-Fa-f]{2})", parsed.query):
        raise ValueError("Malformed URL encoding")
    pairs = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True, errors="strict")
    options = dict(pairs)
    if len(options) != len(pairs) or set(options) != {"sslmode", "sslrootcert"}:
        raise ValueError("Only unique TLS mode and CA parameters are allowed")
    if options["sslmode"] != "verify-full" or options["sslrootcert"] != CONTAINER_CA:
        raise ValueError("verify-full and the mounted CA path are mandatory")


def validate_ca(value):
    path = Path(value)
    if not path.is_file() or not os.access(path, os.R_OK) or path.stat().st_size == 0:
        raise ValueError("Readable CA file required")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.load_verify_locations(cafile=str(path))
    certificates = context.get_ca_certs()
    if not certificates:
        raise ValueError("CA certificates required")
    now = time.time()
    for certificate in certificates:
        if not ssl.cert_time_to_seconds(certificate["notBefore"]) <= now < ssl.cert_time_to_seconds(certificate["notAfter"]):
            raise ValueError("CA outside its validity period")


def read_env_file(value):
    result = {}
    for line in Path(value).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, raw = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or key in result:
            raise ValueError("Invalid or duplicate environment entry")
        fields = shlex.split(raw, comments=False, posix=True)
        if len(fields) != 1 or "$" in fields[0] or "`" in fields[0]:
            raise ValueError("Use literal, nonempty deployment settings")
        result[key] = fields[0]
    return result


def validate_runtime(settings):
    expected = {"SPRING_PROFILES_ACTIVE": "ec2", "SPRING_FLYWAY_ENABLED": "false",
                "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate"}
    if any(settings.get(key) != value for key, value in expected.items()):
        raise ValueError("EC2 profile, Flyway off and validate required")
    # Prevent accidental alternate Spring property sources bypassing the checked URL.
    forbidden = {"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
                 "SPRING_CONFIG_IMPORT", "SPRING_CONFIG_LOCATION", "SPRING_CONFIG_ADDITIONAL_LOCATION",
                 "SPRING_APPLICATION_JSON", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}
    if forbidden.intersection(settings):
        raise ValueError("Runtime property source overrides are disabled")
    if re.search(r"(^|\s)-D", settings.get("JAVA_TOOL_OPTIONS", "")):
        raise ValueError("JVM system property overrides are disabled")
    if settings.get("APP_SECRETS_PATH", "/run/secrets/hobbytree/") != "/run/secrets/hobbytree/":
        raise ValueError("Use the fixed configtree mount")


def validate_jwt(value):
    if not isinstance(value, str) or not value or len(value) > 4096:
        raise ValueError("JWT key must be Base64")
    key = base64.b64decode(value + "=" * (-len(value) % 4), validate=True)
    canonical = base64.b64encode(key).decode("ascii")
    if value.rstrip("=") != canonical.rstrip("=") or ("=" in value and value != canonical):
        raise ValueError("Standard Base64 required")
    if len(key) < 64:
        raise ValueError("JWT key must decode to at least 64 bytes")


def validate_redis_password(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", value):
        raise ValueError("Redis password must be 32-128 URL-safe random characters")


def validate_auth_settings(settings, runtime=False):
    if not re.fullmatch(r"[1-9][0-9]*", settings.get("KAKAO_APP_ID", "")):
        raise ValueError("Positive Kakao app ID required")
    client = settings.get("GOOGLE_CLIENT_ID", "")
    if not re.fullmatch(r"[A-Za-z0-9._-]+\.apps\.googleusercontent\.com", client):
        raise ValueError("Google Web client ID required")
    if settings.get("REDIS_HOST", "redis") != "redis" or settings.get("REDIS_PORT", "6379") != "6379":
        raise ValueError("Use the private Redis service")
    # These deployment files contain identifiers, never credential values.
    forbidden = {"JWT_SECRET", "REDIS_PASSWORD", "DB_PASSWORD", "DB_USERNAME",
                 "AUTH_JWT_SECRET", "AUTH_JWTSECRET", "AUTH_KAKAO_APP_ID", "AUTH_GOOGLE_CLIENT_ID"}
    if forbidden.intersection(settings) or any(k.startswith("SPRING_DATA_REDIS_") for k in settings):
        raise ValueError("Credential and Redis property overrides are disabled")
    if runtime and (settings.get("REDIS_HOST") != "redis" or settings.get("REDIS_PORT") != "6379"):
        raise ValueError("Explicit private Redis endpoint required")


def validate_proxy_settings(settings, runtime=False):
    cidrs = settings.get("AUTH_TRUSTED_PROXY_CIDRS", "").split(",")
    if not 1 <= len(cidrs) <= 32:
        raise ValueError("Explicit ALB subnet CIDRs required")
    for value in cidrs:
        value = value.strip()
        if "/" not in value or "%" in value:
            raise ValueError("Numeric proxy CIDRs required")
        network = ipaddress.ip_network(value, strict=True)
        if network.prefixlen == 0 or (isinstance(network.network_address, ipaddress.IPv6Address) and network.network_address.ipv4_mapped):
            raise ValueError("Wildcard or mapped proxy CIDRs are disabled")
    forbidden = {"AUTH_PROXY_TRUSTED_CIDRS", "AUTH_PROXY_TRUSTEDCIDRS"}
    if forbidden.intersection(settings) or any(k.startswith("SERVER_TOMCAT_REMOTEIP_") for k in settings):
        raise ValueError("Proxy property overrides are disabled")
    if runtime and settings.get("SERVER_FORWARD_HEADERS_STRATEGY") != "none":
        raise ValueError("Keep the original socket peer")


def read_protected_file(path, uid=10001):
    path = Path(path)
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError("Secret symlinks are disabled")
    info, parent = path.stat(), path.parent.stat()
    if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= 4096:
        raise ValueError("Nonempty bounded regular Secret file required")
    if os.name != "posix":
        raise ValueError("Secret permissions must be checked on Linux")
    if (info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)) != (uid, uid, 0o400):
        raise ValueError("Secret file ownership/mode mismatch")
    if (parent.st_uid, parent.st_gid, stat.S_IMODE(parent.st_mode)) != (uid, uid, 0o500):
        raise ValueError("Secret directory ownership/mode mismatch")
    return path.read_text(encoding="utf-8")


def validate_secret_files(directory, redis_directory=None):
    directory = Path(directory)
    if {p.name for p in directory.iterdir()} != {"DB_USERNAME", "DB_PASSWORD", "JWT_SECRET", "REDIS_PASSWORD"}:
        raise ValueError("Unexpected application Secret files")
    validate_jwt(read_protected_file(directory / "JWT_SECRET"))
    password = read_protected_file(directory / "REDIS_PASSWORD")
    validate_redis_password(password)
    for name in ("DB_USERNAME", "DB_PASSWORD"):
        read_protected_file(directory / name)
    if redis_directory is not None:
        import hashlib
        redis_directory = Path(redis_directory)
        if {p.name for p in redis_directory.iterdir()} != {"REDIS_PASSWORD", "users.acl"}:
            raise ValueError("Unexpected Redis Secret files")
        if read_protected_file(redis_directory / "REDIS_PASSWORD", 999) != password:
            raise ValueError("Redis credentials differ")
        expected = "user default on #" + hashlib.sha256(password.encode()).hexdigest() + " ~auth:* +@all\n"
        if read_protected_file(redis_directory / "users.acl", 999) != expected:
            raise ValueError("Redis ACL mismatch")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--env-file")
    group.add_argument("--runtime", action="store_true")
    parser.add_argument("--check-secrets", action="store_true")
    arguments = parser.parse_args()
    try:
        settings = os.environ if arguments.runtime else read_env_file(arguments.env_file)
        if arguments.runtime:
            validate_runtime(settings)
        validate_auth_settings(settings, arguments.runtime)
        validate_proxy_settings(settings, arguments.runtime)
        validate_url(settings.get("DB_URL"))
        validate_ca(CONTAINER_CA if arguments.runtime else settings.get("RDS_CA_BUNDLE", ""))
        if arguments.runtime:
            validate_secret_files("/run/secrets/hobbytree")
        elif arguments.check_secrets:
            validate_secret_files(settings.get("APP_SECRETS_DIR", "/run/hobbytree/secrets"),
                                  settings.get("REDIS_SECRETS_DIR", "/run/hobbytree/redis-secrets"))
    except Exception:
        print("EC2 configuration validation failed: check TLS, auth, Redis and protected Secret files.", file=sys.stderr)
        return 1
    print("EC2 configuration validated; values were not logged.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
