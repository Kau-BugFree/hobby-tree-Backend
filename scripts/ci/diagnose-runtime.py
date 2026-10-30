#!/usr/bin/env python3
"""Collect bounded CI runtime diagnostics without dumping environment or credentials."""

import json
import os
from pathlib import Path
import re
import subprocess

PROJECT = os.environ.get("COMPOSE_PROJECT_NAME", "hobbytree-ci")
if PROJECT != "hobbytree-ci" and not PROJECT.startswith("hobbytree-ci-"):
    raise ValueError("Only isolated hobbytree-ci projects are allowed")
COMPOSE = ["docker", "compose", "-p", PROJECT, "-f", "compose.ec2.yml", "-f", "compose.ci.yml"]


def redact(text, secrets):
    for value in sorted(set(secrets), key=len, reverse=True):
        if len(value) >= 4:
            text = text.replace(value, "[REDACTED]")
    text = re.sub(r"(?i)((?:password|jwt[_-]?secret|access[_-]?token|refresh[_-]?token)\s*[=:]\s*)[^\s,;]+",
                  r"\1[REDACTED]", text)
    # Also mask JWTs embedded in exception text without a key=value prefix.
    text = re.sub(r"\beyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "[REDACTED]", text)
    return text


def read_secrets():
    paths = [Path(os.environ["APP_SECRETS_DIR"]) / name
             for name in ("DB_USERNAME", "DB_PASSWORD", "JWT_SECRET", "REDIS_PASSWORD")]
    paths += [Path(os.environ["CI_FIXTURE_DIR"]) / "postgres" / name
              for name in ("admin-password", "app-password")]
    # Fail closed rather than emit raw logs if credential redaction cannot be prepared.
    return [path.read_text(encoding="utf-8").strip() for path in paths]


def capture(arguments):
    try:
        return subprocess.run(arguments, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              encoding="utf-8", errors="replace", timeout=20, check=False).stdout
    except subprocess.TimeoutExpired:
        return "Diagnostic command timed out.\n"


def main():
    if os.environ.get("HOBBYTREE_CI") != "1":
        raise RuntimeError("CI guard missing")
    secrets = read_secrets()
    print(redact(capture(COMPOSE + ["ps", "--all"]), secrets))
    containers = capture(COMPOSE + ["ps", "--all", "--quiet", "backend"]).splitlines()
    for container in containers:
        if not re.fullmatch(r"[0-9a-f]{12,64}", container):
            continue
        # Only State/RestartCount: never inspect Config.Env or print the full inspect document.
        output = capture(["docker", "inspect", "--format", "{{json .State}}", container])
        try:
            state = json.loads(output)
            summary = {key: state.get(key) for key in ("Status", "ExitCode", "OOMKilled", "Error", "Health")}
            print(redact(json.dumps(summary), secrets))
        except (ValueError, TypeError):
            print("Backend state inspection unavailable.")
        print("Backend restart count:", capture(["docker", "inspect", "--format", "{{.RestartCount}}", container]).strip())
    for service in ("backend", "postgres", "redis"):
        print("Recent redacted logs:", service)
        print(redact(capture(COMPOSE + ["logs", "--no-color", "--tail", "120", service]), secrets))
    print("Health response (if backend is running):")
    print(redact(capture(COMPOSE + ["exec", "-T", "backend", "curl", "--silent", "--show-error",
                                  "--max-time", "5", "--write-out", "\nHTTP %{http_code}\n",
                                  "http://127.0.0.1:8080/actuator/health"]), secrets))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("CI diagnostics unavailable; raw output suppressed to protect credentials.")
