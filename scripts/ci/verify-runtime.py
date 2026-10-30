#!/usr/bin/env python3
"""Verify only the isolated Docker CI project; never emit credentials or raw logs."""

import json
import os
from pathlib import Path
import subprocess
import sys

COMPOSE = ["docker", "compose", "-p", "hobbytree-ci", "-f", "compose.ec2.yml", "-f", "compose.ci.yml"]
URL = "jdbc:postgresql://postgres:5432/hobbytree_ci?sslmode=verify-full&sslrootcert=/etc/hobbytree/certs/rds-ca.pem"


def command(*arguments):
    return subprocess.run(list(arguments), check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, encoding="utf-8", timeout=60).stdout.strip()


def sql(query, database="hobbytree_ci"):
    return command(*COMPOSE, "exec", "-T", "postgres", "psql", "--no-psqlrc", "-qAt",
                   "-v", "ON_ERROR_STOP=1", "-U", "ci_migration", "-d", database, "-c", query)


def request(path):
    output = command(*COMPOSE, "exec", "-T", "backend", "curl", "--silent", "--show-error",
                     "--max-time", "5", "--write-out", "\n%{http_code}", "http://127.0.0.1:8080" + path)
    body, status = output.rsplit("\n", 1)
    return int(status), json.loads(body)


def expect_failure(label, url, ca, network=None, settings=None, with_secret=True, expected=None):
    image = os.environ["BACKEND_IMAGE"]
    arguments = ["docker", "run", "--rm", "--platform", "linux/amd64", "--read-only",
                 "--label", "hobbytree.validation=1",
                 "--cap-drop", "ALL", "--memory", "1g", "--memory-swap", "1g",
                 "--tmpfs", "/tmp:rw,size=64m,mode=1777"]
    if network:
        arguments += ["--network", network]
    environment = {"SPRING_PROFILES_ACTIVE": "ec2", "SPRING_FLYWAY_ENABLED": "false",
                   "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate", "DB_URL": url,
                   "KAKAO_APP_ID": "1", "GOOGLE_CLIENT_ID": "ci.apps.googleusercontent.com",
                   "REDIS_HOST": "redis", "REDIS_PORT": "6379"}
    environment.update(settings or {})
    for key, value in environment.items():
        arguments += ["--env", key + "=" + value]
    arguments += ["--mount", "type=bind,src=" + str(ca) + ",dst=/etc/hobbytree/certs/rds-ca.pem,readonly"]
    if with_secret:
        arguments += ["--mount", "type=bind,src=" + os.environ["APP_SECRETS_DIR"] + ",dst=/run/secrets/hobbytree,readonly"]
    result = subprocess.run(arguments + [image], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            encoding="utf-8", timeout=60)
    if result.returncode == 0 or (expected or "EC2 configuration validation failed") not in result.stdout:
        raise RuntimeError("Expected failure not observed: " + label)
    print("PASS: " + label)


def main():
    if os.environ.get("HOBBYTREE_CI") != "1":
        raise RuntimeError("CI guard missing")
    status, body = request("/actuator/health")
    if status != 200 or body != {"status": "UP"}:
        raise RuntimeError("Health response mismatch")
    for endpoint in ("/actuator/env", "/actuator/configprops"):
        if request(endpoint)[0] != 401:
            raise RuntimeError("Sensitive actuator endpoint exposed")
    backend = command(*COMPOSE, "ps", "-q", "backend")
    inspection = json.loads(command("docker", "inspect", backend))[0]
    environment = dict(item.split("=", 1) for item in inspection["Config"]["Env"])
    if environment.get("SPRING_FLYWAY_ENABLED") != "false" or environment.get("SPRING_JPA_HIBERNATE_DDL_AUTO") != "validate":
        raise RuntimeError("Unsafe runtime settings")
    if any(key in environment for key in ("DB_PASSWORD", "JWT_SECRET", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN")):
        raise RuntimeError("Credential environment leak")
    host = inspection["HostConfig"]
    if host["Memory"] != 1073741824 or host["MemorySwap"] != 1073741824 or not host["ReadonlyRootfs"]:
        raise RuntimeError("Container limits mismatch")
    if inspection["Config"]["User"] != "10001:10001" or inspection["RestartCount"] != 0:
        raise RuntimeError("Runtime user or unexpected restart")
    if host["PortBindings"]["8080/tcp"][0]["HostIp"] != "127.0.0.1":
        raise RuntimeError("Non-loopback binding")
    if host["LogConfig"]["Config"].get("max-size") != "10m" or host["LogConfig"]["Config"].get("max-file") != "3":
        raise RuntimeError("Log rotation mismatch")
    if sql("SELECT count(*) FROM pg_tables WHERE schemaname='public'") != "18":
        raise RuntimeError("Unexpected table count")
    if sql("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='users' AND column_name IN ('restorable_until','status_before_withdrawal','anonymized_at')") != "3":
        raise RuntimeError("V2 authentication columns missing")
    if command(*COMPOSE, "exec", "-T", "redis", "redis-cli", "ping") != "PONG":
        raise RuntimeError("Redis unavailable")
    if sql("SELECT to_regclass('public.flyway_schema_history') IS NULL") != "t":
        raise RuntimeError("Flyway history unexpectedly exists")
    if sql("SELECT has_schema_privilege('ci_app','public','CREATE')") != "f":
        raise RuntimeError("Application has DDL permission")
    if sql("SELECT count(*) > 0 AND bool_and(s.ssl) FROM pg_stat_activity a JOIN pg_stat_ssl s USING(pid) WHERE a.usename='ci_app'") != "t":
        raise RuntimeError("Application TLS connection not confirmed")
    network = next(iter(inspection["NetworkSettings"]["Networks"]))
    fixture = Path(os.environ["CI_FIXTURE_DIR"])
    ca = fixture / "ca.pem"
    for mode in ("disable", "require", "prefer", "allow", "verify-ca"):
        expect_failure("reject TLS mode " + mode, URL.replace("verify-full", mode), ca)
    expect_failure("reject corrupt CA", URL, fixture / "invalid-ca.pem")
    expect_failure("reject Flyway enable", URL, ca, settings={"SPRING_FLYWAY_ENABLED": "true"})
    expect_failure("reject ddl-auto update", URL, ca, settings={"SPRING_JPA_HIBERNATE_DDL_AUTO": "update"})
    expect_failure("missing configtree fails before DB access", URL, ca, with_secret=False,
                   expected="configtree:/run/secrets/hobbytree/")
    expect_failure("JDBC rejects certificate hostname mismatch", URL.replace("//postgres:", "//postgres-wrong-host:"),
                   ca, network=network, expected="could not be verified")
    expect_failure("Hibernate validate rejects an empty schema",
                   URL.replace("/hobbytree_ci?", "/hobbytree_ci_empty?"), ca, network=network,
                   expected="Schema validation: missing table")
    if sql("SELECT count(*) FROM pg_tables WHERE schemaname='public'", "hobbytree_ci_empty") != "0":
        raise RuntimeError("Hibernate or Flyway changed the empty test schema")
    if sql("SELECT to_regclass('public.flyway_schema_history') IS NULL") != "t":
        raise RuntimeError("Negative tests created Flyway history")
    print("PASS: amd64 container, health, app TLS, validate, Flyway off, resource limits and negative cases")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        # All RuntimeError messages above are fixed check labels, never subprocess output.
        print("CI runtime verification failed: " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("CI runtime verification failed; credential values and raw logs are suppressed.", file=sys.stderr)
        sys.exit(1)
