import importlib.util
import io
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
from contextlib import redirect_stderr

SPEC = importlib.util.spec_from_file_location(
    "fetch_secrets", Path(__file__).resolve().parents[1] / "fetch-ec2-secrets.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SecretRetrievalTests(unittest.TestCase):
    def test_secret_output_cannot_target_root_or_arbitrary_host_paths(self):
        for path in ("/", "/run", "/run/hobbytree", "/etc/secrets", "relative", "/run/hobbytree/../secrets"):
            with self.assertRaises(ValueError):
                MODULE.validate_secret_directory(path)
        MODULE.validate_secret_directory("/run/hobbytree/secrets")

    def response(self, value):
        return subprocess.CompletedProcess([], 0, stdout=json.dumps(value))

    @patch.object(MODULE.subprocess, "run")
    def test_only_username_and_password_are_forwarded_without_shell(self, run):
        run.return_value = self.response({
            "username": "hobbytree_app", "password": "dummy-$'\"\\=value",
            "host": "ignored.invalid", "migration_password": "unused-test-value",
        })
        credentials = MODULE.read_credentials("test-secret", "ap-northeast-2", "hobbytree_app")
        self.assertEqual(set(credentials), {"DB_USERNAME", "DB_PASSWORD"})
        self.assertEqual(credentials["DB_PASSWORD"], "dummy-$'\"\\=value")
        self.assertIsInstance(run.call_args.args[0], list)
        self.assertNotIn("shell", run.call_args.kwargs)
        self.assertEqual(run.call_args.kwargs["stderr"], subprocess.DEVNULL)

    @patch.object(MODULE.subprocess, "run")
    def test_rejects_administrator_before_aws_request(self, run):
        for username in ("postgres", "POSTGRES", "rdsadmin", ""):
            with self.assertRaises(ValueError):
                MODULE.read_credentials("test", "ap-northeast-2", username)
        run.assert_not_called()

    @patch.object(MODULE.subprocess, "run")
    def test_rejects_wrong_or_migration_account(self, run):
        run.return_value = self.response({"username": "hobbytree_migration", "password": "dummy"})
        with self.assertRaises(ValueError):
            MODULE.read_credentials("test", "ap-northeast-2", "hobbytree_app")

    @patch.object(MODULE.subprocess, "run")
    def test_rejects_malformed_or_unrepresentable_secrets(self, run):
        for value in ([], {}, {"username": "hobbytree_app", "password": ""},
                      {"username": "hobbytree_app", "password": "\nvalue"},
                      {"username": "hobbytree_app", "password": "value "}):
            run.return_value = self.response(value)
            with self.assertRaises(ValueError):
                MODULE.read_credentials("test", "ap-northeast-2", "hobbytree_app")

    @patch.object(MODULE, "read_credentials", side_effect=ValueError("sensitive-test-sentinel"))
    def test_failure_does_not_print_exception_or_secret(self, read):
        output = io.StringIO()
        arguments = ["fetch-ec2-secrets.py", "--secret-id", "test", "--expected-username", "hobbytree_app", "--auth-secret-id", "test-auth"]
        with patch.object(MODULE.sys, "argv", arguments), patch.object(MODULE, "require_linux_root"), redirect_stderr(output):
            self.assertEqual(MODULE.main(), 1)
        read.assert_called_once()
        self.assertNotIn("sensitive-test-sentinel", output.getvalue())
        self.assertIn("Secret preparation failed", output.getvalue())


if __name__ == "__main__":
    unittest.main()
