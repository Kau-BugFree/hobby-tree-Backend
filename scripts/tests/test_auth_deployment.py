import base64
from contextlib import redirect_stderr
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import unittest
from unittest.mock import patch
import uuid


ROOT = Path(__file__).resolve().parents[2]


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


VALIDATOR = load("auth_validator", "validate-ec2-config.py")
FETCH = load("auth_fetch", "fetch-ec2-secrets.py")
KEY = base64.b64encode(bytes(range(64))).decode()
PASSWORD = "synthetic-test-password-" + "x" * 32


class AuthDeploymentTests(unittest.TestCase):
    def test_jwt_decoded_byte_length_and_standard_base64(self):
        VALIDATOR.validate_jwt(KEY)
        VALIDATOR.validate_jwt(KEY.rstrip("="))  # Java Basic decoder accepts unpadded input.
        for value in (None, "", "a" * 64, "not/base64!", KEY + "\n",
                      base64.b64encode(bytes(63)).decode(), "_" * 88):
            with self.subTest(case=type(value).__name__), self.assertRaises((ValueError, TypeError)):
                VALIDATOR.validate_jwt(value)

    def test_redis_password_cannot_inject_config_or_be_empty(self):
        VALIDATOR.validate_redis_password(PASSWORD)
        for value in (None, "", "short", "x" * 129, "x" * 32 + "\n", "x" * 32 + '"', "x" * 32 + " "):
            with self.assertRaises(ValueError):
                VALIDATOR.validate_redis_password(value)

    def test_private_redis_and_no_credential_or_property_overrides(self):
        valid = {"KAKAO_APP_ID": "123", "GOOGLE_CLIENT_ID": "ci.apps.googleusercontent.com",
                 "REDIS_HOST": "redis", "REDIS_PORT": "6379"}
        VALIDATOR.validate_auth_settings(valid, True)
        for key, value in (("KAKAO_APP_ID", "0"), ("GOOGLE_CLIENT_ID", ""),
                           ("REDIS_HOST", "public.invalid"), ("REDIS_PORT", "16379"),
                           ("JWT_SECRET", KEY), ("REDIS_PASSWORD", PASSWORD),
                           ("SPRING_DATA_REDIS_URL", "redis://elsewhere"),
                           ("AUTH_JWTSECRET", KEY)):
            with self.assertRaises(ValueError):
                VALIDATOR.validate_auth_settings(dict(valid, **{key: value}), True)

    @patch.object(FETCH, "read_secret")
    def test_auth_secret_maps_only_allowed_keys_and_rejects_missing_fields(self, read):
        read.return_value = {"JWT_SECRET": KEY, "REDIS_PASSWORD": PASSWORD,
                             "DB_PASSWORD": "must-not-reach-redis"}
        self.assertEqual(set(FETCH.read_auth_credentials("mock", "test")), {"JWT_SECRET", "REDIS_PASSWORD"})
        for fields in ({}, {"JWT_SECRET": KEY}, {"REDIS_PASSWORD": PASSWORD}):
            read.return_value = fields
            with self.assertRaises((ValueError, TypeError)):
                FETCH.read_auth_credentials("mock", "test")

    @patch.object(FETCH, "require_linux_root")
    @patch.object(FETCH, "write_credentials")
    @patch.object(FETCH, "read_auth_credentials", side_effect=ValueError("private-sentinel"))
    @patch.object(FETCH, "read_credentials", return_value={"DB_USERNAME": "ci_app", "DB_PASSWORD": "dummy"})
    def test_fetch_failure_writes_nothing_and_does_not_log_secret(self, db, auth, write, root):
        args = ["fetch", "--secret-id", "db", "--auth-secret-id", "auth", "--expected-username", "ci_app"]
        output = io.StringIO()
        with patch.object(FETCH.sys, "argv", args), redirect_stderr(output):
            self.assertEqual(FETCH.main(), 1)
        write.assert_not_called()
        self.assertNotIn("private-sentinel", output.getvalue())

    @patch.object(FETCH, "require_linux_root")
    @patch.object(FETCH, "read_credentials")
    def test_overlapping_secret_directories_rejected_before_fetch(self, read, root):
        args = ["fetch", "--secret-id", "db", "--auth-secret-id", "auth", "--expected-username", "ci_app",
                "--redis-output-dir", "/run/hobbytree/secrets/nested"]
        with patch.object(FETCH.sys, "argv", args), redirect_stderr(io.StringIO()):
            self.assertEqual(FETCH.main(), 1)
        read.assert_not_called()

    def test_systemd_restarts_only_backend_and_preserves_redis(self):
        unit = (ROOT / "deploy/hobbytree-backend.service").read_text()
        self.assertIn("--no-recreate --wait --wait-timeout 120 redis", unit)
        self.assertIn("--no-deps --force-recreate --wait --wait-timeout 120 backend", unit)
        self.assertIn("compose.ec2.yml stop backend", unit)
        self.assertIn("--check-secrets", unit)

    def test_both_workflows_run_auth_integration_before_export(self):
        for name in ("docker-validation.yml", "ecr-push.yml"):
            workflow = (ROOT / ".github/workflows" / name).read_text()
            self.assertIn("target: auth-tests", workflow)
            self.assertIn("python3 scripts/ci/verify-auth-deployment.py", workflow)
            self.assertIn("/redis-secrets/users.acl", workflow)
        workflow = (ROOT / ".github/workflows/ecr-push.yml").read_text()
        self.assertLess(workflow.index("verify-auth-deployment.py"), workflow.index("Export the exact validated image"))
        cmd = next(line for line in (ROOT / "Dockerfile").read_text().splitlines() if line.startswith("CMD "))
        self.assertIsInstance(json.loads(cmd[4:]), list)
        self.assertIn('AUTH_INTEGRATION_TEST: "true"', (ROOT / "compose.ci.yml").read_text())


@unittest.skipUnless(os.name == "posix" and os.geteuid() == 0, "Linux root filesystem permission tests")
class LinuxSecretFilesTests(unittest.TestCase):
    def setUp(self):
        self.directory = Path("/run/hobbytree") / ("test-" + uuid.uuid4().hex)
        self.app, self.redis = self.directory / "app", self.directory / "redis"
        self.credentials = {"DB_USERNAME": "ci_app", "DB_PASSWORD": "dummy",
                            "JWT_SECRET": KEY, "REDIS_PASSWORD": PASSWORD}
        import hashlib
        self.redis_credentials = {"REDIS_PASSWORD": PASSWORD,
            "users.acl": "user default on #" + hashlib.sha256(PASSWORD.encode()).hexdigest() + " ~auth:* +@all\n"}
        FETCH.write_credentials(str(self.app), self.credentials)
        FETCH.write_credentials(str(self.redis), self.redis_credentials, uid=999)

    def tearDown(self):
        # Only the unique test directory under the fixed runtime root is removed.
        self.assertEqual(self.directory.parent, Path("/run/hobbytree"))
        self.assertTrue(self.directory.name.startswith("test-"))
        shutil.rmtree(self.directory)

    def test_real_file_ownership_permissions_and_isolation(self):
        VALIDATOR.validate_secret_files(self.app, self.redis)
        self.assertEqual(set(p.name for p in self.redis.iterdir()), {"REDIS_PASSWORD", "users.acl"})
        self.assertEqual((self.redis / "users.acl").stat().st_uid, 999)
        self.assertNotIn(PASSWORD, (self.redis / "users.acl").read_text())
        FETCH.write_credentials(str(self.app), self.credentials)  # repeat after 0500 directory
        VALIDATOR.validate_secret_files(self.app, self.redis)

    def test_world_readable_wrong_owner_missing_or_symlink_jwt_fails(self):
        key = self.app / "JWT_SECRET"
        os.chmod(key, 0o444)
        with self.assertRaises(ValueError):
            VALIDATOR.validate_secret_files(self.app, self.redis)
        os.chmod(key, 0o400)
        os.chown(key, 0, 0)
        with self.assertRaises(ValueError):
            VALIDATOR.validate_secret_files(self.app, self.redis)
        key.unlink()
        with self.assertRaises((OSError, ValueError)):
            VALIDATOR.validate_secret_files(self.app, self.redis)
        key.symlink_to(self.app / "REDIS_PASSWORD")
        with self.assertRaises(ValueError):
            VALIDATOR.validate_secret_files(self.app, self.redis)

    def test_redis_password_mismatch_is_rejected(self):
        FETCH.write_credentials(str(self.redis), dict(self.redis_credentials, REDIS_PASSWORD="other-" + PASSWORD), uid=999)
        with self.assertRaises(ValueError):
            VALIDATOR.validate_secret_files(self.app, self.redis)

    def test_symlink_directory_is_not_written(self):
        link = self.directory / "link"
        link.symlink_to(self.app, target_is_directory=True)
        with self.assertRaises(ValueError):
            FETCH.write_credentials(str(link), self.credentials)


if __name__ == "__main__":
    unittest.main()
