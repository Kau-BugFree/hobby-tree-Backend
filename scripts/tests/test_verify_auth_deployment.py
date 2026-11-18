import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "verify_auth_deployment", Path(__file__).resolve().parents[1] / "ci" / "verify-auth-deployment.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def fake_result(stdout, returncode):
    return subprocess.CompletedProcess(args=[], returncode=returncode, stdout=stdout, stderr="")


class RunCheckFlagTests(unittest.TestCase):
    @patch.object(MODULE.subprocess, "run")
    def test_check_true_raises_on_a_nonzero_exit_without_inspecting_output(self, run):
        run.return_value = fake_result("NOAUTH Authentication required.", 1)
        with self.assertRaises(RuntimeError):
            MODULE.run("redis-cli", "ping")

    @patch.object(MODULE.subprocess, "run")
    def test_check_false_returns_the_output_of_a_nonzero_exit_instead_of_raising(self, run):
        run.return_value = fake_result("NOAUTH Authentication required.", 1)
        self.assertIn("NOAUTH", MODULE.run("redis-cli", "ping", check=False))

    @patch.object(MODULE.subprocess, "run")
    def test_check_false_still_returns_output_on_a_zero_exit(self, run):
        run.return_value = fake_result("PONG", 0)
        self.assertEqual(MODULE.run("redis-cli", "ping", check=False), "PONG")

    @patch.object(MODULE, "run")
    def test_redis_helper_forwards_the_check_flag(self, run):
        run.return_value = "NOPERM no permissions"
        MODULE.redis("SET", "outside-auth-scope", "ci-marker", check=False)
        self.assertEqual(run.call_args.kwargs.get("check"), False)
        MODULE.redis("PING")
        self.assertEqual(run.call_args.kwargs.get("check"), True)


if __name__ == "__main__":
    unittest.main()
