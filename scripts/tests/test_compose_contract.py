"""Render Compose without a Docker daemon or any credential files."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ComposeContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("docker"):
            raise unittest.SkipTest("Docker Compose CLI unavailable")
        probe = subprocess.run(["docker", "compose", "version"], capture_output=True)
        if probe.returncode:
            raise unittest.SkipTest("Docker Compose CLI unavailable")

    def render(self, ci=False):
        # No ambient dotenv or deployment credentials are used.
        environment = {k: v for k, v in os.environ.items()
                       if k not in {"JWT_SECRET", "REDIS_PASSWORD", "BACKEND_BIND_IP", "COMPOSE_FILE", "COMPOSE_PROFILES"}}
        environment.update(BACKEND_IMAGE="hobbytree/backend:ci", KAKAO_APP_ID="1",
            GOOGLE_CLIENT_ID="ci.apps.googleusercontent.com", BACKEND_BIND_IP="127.0.0.1" if ci else "0.0.0.0",
            DB_URL="jdbc:postgresql://postgres:5432/hobbytree_ci?sslmode=verify-full&sslrootcert=/etc/hobbytree/certs/rds-ca.pem",
            APP_SECRETS_DIR="/run/hobbytree/ci/secrets", REDIS_SECRETS_DIR="/run/hobbytree/ci/redis-secrets",
            RDS_CA_BUNDLE="/tmp/hobbytree-ci-ca.pem", CI_FIXTURE_DIR="/tmp/hobbytree-ci")
        command = ["docker", "compose", "--env-file", os.devnull, "-f", "compose.ec2.yml"]
        if ci:
            command += ["-f", "compose.ci.yml", "--profile", "auth-tests"]
        result = subprocess.run(command + ["config", "--format", "json"], cwd=ROOT, env=environment,
                                capture_output=True, encoding="utf-8", timeout=30)
        self.assertEqual(result.returncode, 0, "Compose configuration failed")
        return json.loads(result.stdout)

    def test_ec2_isolation_credentials_resources_and_alb_binding(self):
        config = self.render()
        backend, redis = config["services"]["backend"], config["services"]["redis"]
        self.assertEqual(backend["ports"][0]["host_ip"], "0.0.0.0")
        self.assertEqual(redis.get("ports", []), [])
        self.assertEqual(set(redis["networks"]), {"auth"})
        self.assertTrue(config["networks"]["auth"]["internal"])
        self.assertEqual(redis["command"], ["redis-server", "/etc/redis/redis.conf"])
        self.assertEqual(redis.get("environment", {}), {})
        self.assertEqual(int(redis["mem_limit"]), 384 * 1024 * 1024)
        self.assertEqual(int(backend["mem_limit"]), 1024 * 1024 * 1024)
        self.assertNotIn("JWT_SECRET", backend["environment"])
        self.assertNotIn("REDIS_PASSWORD", backend["environment"])
        self.assertEqual(backend["environment"]["REDIS_HOST"], "redis")
        self.assertEqual(backend["environment"]["SPRING_FLYWAY_ENABLED"], "false")
        mounts = {v["target"]: v for v in redis["volumes"]}
        self.assertTrue(mounts["/run/secrets/redis"]["read_only"])
        self.assertNotIn("/run/secrets/hobbytree", mounts)
        self.assertEqual(mounts["/data"]["type"], "volume")

    def test_ci_uses_same_redis_security_and_enables_auth_suite(self):
        config = self.render(True)
        services = config["services"]
        self.assertEqual(services["backend"]["ports"][0]["host_ip"], "127.0.0.1")
        self.assertEqual(services["auth-tests"]["environment"]["AUTH_INTEGRATION_TEST"], "true")
        self.assertEqual(services["redis"]["command"], ["redis-server", "/etc/redis/redis.conf"])
        self.assertNotIn("REDIS_PASSWORD", services["backend"]["environment"])
        self.assertTrue(config["networks"]["default"]["internal"])

    def test_failure_diagnostics_renders_compose_after_sudo_env_reset(self):
        workflow = (ROOT / ".github/workflows/docker-validation.yml").read_text()
        command = re.search(
            r"--preserve-env=([A-Z0-9_,]+)\s+python3 scripts/ci/diagnose-runtime\.py", workflow)
        self.assertIsNotNone(command)
        preserved = set(command.group(1).split(","))
        # Only the job's public CI fixtures, never service credentials or ambient dotenv.
        settings = {
            "HOBBYTREE_CI": "1", "BACKEND_IMAGE": "hobbytree/backend:ci",
            "DB_URL": "jdbc:postgresql://postgres:5432/hobbytree_ci?sslmode=verify-full&sslrootcert=/etc/hobbytree/certs/rds-ca.pem",
            "CI_FIXTURE_DIR": "/tmp/hobbytree-ci", "RDS_CA_BUNDLE": "/tmp/hobbytree-ci/ca.pem",
            "APP_SECRETS_DIR": "/run/hobbytree/ci/secrets", "KAKAO_APP_ID": "1",
            "GOOGLE_CLIENT_ID": "ci.apps.googleusercontent.com",
        }
        system = {k: v for k, v in os.environ.items()
                  if k.upper() in {"PATH", "SYSTEMROOT", "COMSPEC", "TEMP", "TMP", "HOME", "USERPROFILE"}}

        def render(retained):
            environment = dict(system, **{k: v for k, v in settings.items() if k in retained})
            return subprocess.run(
                ["docker", "compose", "--env-file", os.devnull, "-f", "compose.ec2.yml",
                 "-f", "compose.ci.yml", "config", "--quiet"],
                cwd=ROOT, env=environment, capture_output=True, encoding="utf-8", timeout=30)

        self.assertEqual(render(preserved).returncode, 0, "Diagnostic sudo environment cannot render Compose")
        for missing in ("KAKAO_APP_ID", "GOOGLE_CLIENT_ID"):
            with self.subTest(missing=missing):
                rejected = render(preserved - {missing})
                self.assertNotEqual(rejected.returncode, 0)
                self.assertIn(missing, rejected.stderr)


if __name__ == "__main__":
    unittest.main()
