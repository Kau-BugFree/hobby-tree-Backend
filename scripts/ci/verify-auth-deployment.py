#!/usr/bin/env python3
"""Exercise isolated CI auth tests, Redis authentication, persistence and failures.

Never print subprocess output: failed assertions may contain issued JWTs.
"""
import json
import os
import re
import subprocess
import sys
import time
import uuid

PROJECT = os.environ.get("COMPOSE_PROJECT_NAME", "hobbytree-ci")
if PROJECT != "hobbytree-ci" and not PROJECT.startswith("hobbytree-ci-"):
    raise ValueError("Only isolated hobbytree-ci projects are allowed")
COMPOSE = ["docker", "compose", "-p", PROJECT, "-f", "compose.ec2.yml", "-f", "compose.ci.yml"]


def run(*args, check=True):
    result = subprocess.run(list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            encoding="utf-8", timeout=300)
    if check and result.returncode:
        raise RuntimeError("Isolated CI command failed; raw output suppressed")
    return result.stdout.strip()


def redis(*args):
    return run(*COMPOSE, "exec", "-T", "redis", "sh", "-ec",
               'redis-cli --no-auth-warning --askpass "$@" < /run/secrets/redis/REDIS_PASSWORD 2>/dev/null',
               "sh", *args)


def http_status(path="/actuator/health", post=False):
    args = [*COMPOSE, "exec", "-T", "backend", "curl", "--silent", "--max-time", "5",
            "--output", "/dev/null", "--write-out", "%{http_code}"]
    if post:
        args += ["--header", "Content-Type: application/json", "--data", "{}"]
    return run(*args, "http://127.0.0.1:8080" + path)


def wait_healthy():
    for _ in range(60):
        if http_status() == "200":
            return
        time.sleep(1)
    raise RuntimeError("Backend did not recover")


def sql(query):
    return run(*COMPOSE, "exec", "-T", "postgres", "psql", "--no-psqlrc", "-qAt",
               "-v", "ON_ERROR_STOP=1", "-U", "ci_migration", "-d", "hobbytree_ci", "-c", query)


def protected_status(token):
    # Pass the synthetic JWT through stdin, never argv/environment or logs.
    result = subprocess.run(
        [*COMPOSE, "exec", "-T", "backend", "curl", "--silent", "--max-time", "5",
         "--output", "/dev/null", "--write-out", "%{http_code}", "--header", "@-",
         "http://127.0.0.1:8080/api/v1/nonexistent-protected-resource"],
        input="Authorization: Bearer " + token + "\n", encoding="utf-8",
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
    if result.returncode:
        raise RuntimeError("Protected CI request failed; raw output suppressed")
    return result.stdout.strip()


def create_outage_probe():
    # This runs only after all three service project labels have been checked.
    identity = "ci-outage-" + uuid.uuid4().hex
    user = int(sql("INSERT INTO users(provider,provider_user_id) VALUES ('KAKAO','" + identity
                   + "') RETURNING user_id"))
    sid = uuid.uuid4().hex
    session = "auth:session:" + str(user)
    redis("HSET", session, "sid", sid, "refreshHash", "ci-probe", "expiresAt", str(int(time.time()) + 900))
    redis("EXPIRE", session, "900")
    # Sign inside the runtime UID10001 container. A normal CI runner cannot read the
    # host's 0400 key, and the key must not be copied to the test controller.
    code = """
import base64, hashlib, hmac, json, sys, time, uuid
from pathlib import Path
def encode(value):
    return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=")
now = int(time.time())
message = encode({"alg":"HS256","typ":"JWT"}) + b"." + encode({
    "iss":"hobbytree", "aud":["hobbytree-api"], "sub":sys.argv[1], "sid":sys.argv[2],
    "token_type":"access", "role":"USER", "jti":uuid.uuid4().hex, "iat":now, "exp":now+900})
key = base64.b64decode(Path("/run/secrets/hobbytree/JWT_SECRET").read_text())
def sign(secret):
    signature = base64.urlsafe_b64encode(hmac.new(secret, message, hashlib.sha256).digest()).rstrip(b"=")
    return (message+b"."+signature).decode()
print(json.dumps([sign(key), sign(bytes([key[0]^1])+key[1:])]))
"""
    token, wrong_key_token = json.loads(run(*COMPOSE, "exec", "-T", "backend", "python3", "-c", code, str(user), sid))
    return user, session, token, wrong_key_token



def verify_proxy_rate_limits():
    # CI-only wire test: real container peers on a separate documentation-range network.
    if os.environ.get("AUTH_TRUSTED_PROXY_CIDRS") != "192.0.2.10/32":
        raise RuntimeError("Use the isolated CI proxy configuration")
    backend = json.loads(run("docker", "inspect", run(*COMPOSE, "ps", "-q", "backend")))[0]
    network = next(name for name in backend["NetworkSettings"]["Networks"] if name.endswith("_auth"))
    info = json.loads(run("docker", "network", "inspect", network))[0]
    if info["Labels"].get("com.docker.compose.project") != PROJECT or not info["Internal"]:
        raise RuntimeError("Proxy probe requires this project's internal network")
    addresses = ("203.0.113.100", "203.0.113.101", "192.0.2.10", "192.0.2.11")
    import hashlib
    keys = ["auth:login-rate:" + hashlib.sha256(ip.encode()).hexdigest() for ip in addresses]
    redis("DEL", *keys)
    code = """
import sys, urllib.request, urllib.error
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def request(xff):
    r = urllib.request.Request("http://backend:8080/api/v1/auth/social-login", data=b"{broken",
        headers={"Content-Type":"application/json", "X-Forwarded-For":xff})
    try:
        return opener.open(r, timeout=5).status
    except urllib.error.HTTPError as e:
        return e.code
if sys.argv[1] == "trusted":
    for i in range(20):
        assert request("198.51.100.%s, 203.0.113.100" % i) == 400
    assert request("198.51.100.200, 203.0.113.100") == 429
    assert request("203.0.113.100, 203.0.113.101") == 400
    for i in range(20):
        assert request("198.51.100.%s, 192.0.2.10" % i) == 400
    assert request("198.51.100.200, 192.0.2.10") == 429
else:
    for i in range(20):
        assert request("203.0.113.%s" % i) == 400
    assert request("203.0.113.200") == 429
"""
    try:
        for address, mode in (("192.0.2.10", "trusted"), ("192.0.2.11", "untrusted")):
            run("docker", "run", "--rm", "--network", network, "--ip", address,
                "--label", "hobbytree.validation=1", "--label", "hobbytree.validation.project=" + PROJECT,
                "--read-only", "--cap-drop", "ALL", "--memory", "128m", "--memory-swap", "128m",
                "--entrypoint", "python3", os.environ["BACKEND_IMAGE"], "-c", code, mode)
        for key, count in zip(keys, ("21", "1", "21", "21")):
            if redis("GET", key) != count:
                raise RuntimeError("Docker socket peer/client rate identity mismatch")
    finally:
        redis("DEL", *keys)
    print("PASS: real Docker trusted/untrusted peers, forged prefixes, private clients and independent login limits")


def main():
    if os.environ.get("HOBBYTREE_CI") != "1":
        raise RuntimeError("CI guard missing")
    for service in ("backend", "redis", "postgres"):
        identifier = run(*COMPOSE, "ps", "-q", service)
        info = json.loads(run("docker", "inspect", identifier))[0]
        if info["Config"]["Labels"].get("com.docker.compose.project") != PROJECT:
            raise RuntimeError("Unexpected project")
    redis_id = run(*COMPOSE, "ps", "-q", "redis")
    info = json.loads(run("docker", "inspect", redis_id))[0]
    if info["HostConfig"].get("PortBindings"):
        raise RuntimeError("Redis host port exposed")
    if info["Config"].get("User") != "999:999":
        raise RuntimeError("Redis runtime UID mismatch")
    if any(v.split("=", 1)[0] in {"REDIS_PASSWORD", "JWT_SECRET", "DB_PASSWORD"}
           for v in info["Config"]["Env"]):
        raise RuntimeError("Redis credential environment leak")
    if any(m["Destination"] == "/run/secrets/hobbytree" for m in info["Mounts"]):
        raise RuntimeError("Backend secrets mounted into Redis")
    if "NOAUTH" not in run(*COMPOSE, "exec", "-T", "redis", "redis-cli", "ping"):
        raise RuntimeError("Unauthenticated Redis accepted")
    if redis("PING") != "PONG":
        raise RuntimeError("Authenticated Redis failed")
    bad_auth = subprocess.run([*COMPOSE, "exec", "-T", "redis", "redis-cli", "--askpass", "ping"],
                              input="definitely-wrong-ci-password\n", encoding="utf-8",
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
    if "WRONGPASS" not in bad_auth.stdout + bad_auth.stderr:
        raise RuntimeError("Wrong Redis password accepted")
    if "NOPERM" not in redis("SET", "outside-auth-scope", "ci-marker"):
        raise RuntimeError("Redis key scope not enforced")
    # The image CMD verifies a nonempty test report with no skipped or failed tests.
    output = run(*COMPOSE, "run", "--rm", "--no-deps", "auth-tests")
    summary = re.search(r"AUTH_INTEGRATION_RESULT tests=18 failures=0 errors=0 skipped=0", output)
    if not summary:
        raise RuntimeError("Expected all 18 authentication tests with no skips")
    print(summary.group())
    proxy_summary = re.search(r"PROXY_INTEGRATION_RESULT tests=8 failures=0 errors=0 skipped=0", output)
    if not proxy_summary:
        raise RuntimeError("Expected all 8 proxy integration tests with no skips")
    print(proxy_summary.group())
    verify_proxy_rate_limits()
    user, session, token, wrong_key_token = create_outage_probe()
    # 404 proves authorization passed and reached the absent test resource; 401 would not.
    if protected_status(token) != "404":
        raise RuntimeError("Valid protected CI probe did not authenticate")
    if protected_status(wrong_key_token) != "401":
        raise RuntimeError("JWT signed with a different key was accepted")
    print("PASS: actual protected API accepts valid JWT and rejects a different signing key")
    key = "auth:ci-persistence:" + uuid.uuid4().hex
    if redis("SET", key, "ci-marker", "EX", "300") != "OK":
        raise RuntimeError("Redis write failed")
    try:
        run(*COMPOSE, "stop", "redis")
        if http_status() != "503" or http_status("/api/v1/auth/social-login", True) != "500":
            raise RuntimeError("Redis failure did not fail closed")
        if protected_status(token) != "500":
            raise RuntimeError("Protected API bypassed actual Redis outage")
    finally:
        run(*COMPOSE, "start", "redis")
    wait_healthy()
    if redis("GET", key) != "ci-marker":
        raise RuntimeError("Redis AOF restart persistence failed")
    if protected_status(token) != "404":
        raise RuntimeError("Protected API did not recover its session after Redis restart")
    run(*COMPOSE, "up", "-d", "--no-build", "--no-deps", "--force-recreate",
        "--wait", "--wait-timeout", "120", "redis")
    wait_healthy()
    if redis("GET", key) != "ci-marker":
        raise RuntimeError("Redis volume did not survive container replacement")
    if protected_status(token) != "404":
        raise RuntimeError("Protected session did not survive Redis replacement")
    redis_id = run(*COMPOSE, "ps", "-q", "redis")
    run(*COMPOSE, "up", "-d", "--no-build", "--no-deps", "--force-recreate",
        "--wait", "--wait-timeout", "180", "backend")
    if run(*COMPOSE, "ps", "-q", "redis") != redis_id:
        raise RuntimeError("Backend deployment recreated Redis")
    if protected_status(token) != "404":
        raise RuntimeError("Protected API did not recover after backend deployment")
    redis("DEL", key)
    redis("DEL", session)
    sql("DELETE FROM users WHERE user_id=" + str(user))
    print("PASS: protected API returns 500 during actual Redis outage and recovers its AOF session")
    print("PASS: Redis auth, fail closed, AOF recovery and backend-only deployment")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Auth deployment CI failed; credential values and raw logs suppressed.", file=sys.stderr)
        sys.exit(1)
