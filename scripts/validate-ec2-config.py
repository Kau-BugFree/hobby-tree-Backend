#!/usr/bin/env python3
"""Fail closed on insecure EC2 JDBC/TLS settings; never print configuration values."""

import argparse
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--env-file")
    group.add_argument("--runtime", action="store_true")
    arguments = parser.parse_args()
    try:
        settings = os.environ if arguments.runtime else read_env_file(arguments.env_file)
        if arguments.runtime:
            validate_runtime(settings)
        validate_url(settings.get("DB_URL"))
        validate_ca(CONTAINER_CA if arguments.runtime else settings.get("RDS_CA_BUNDLE", ""))
    except Exception:
        print("EC2 configuration validation failed: check TLS URL, CA file and runtime settings.", file=sys.stderr)
        return 1
    print("EC2 TLS configuration validated; values were not logged.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
