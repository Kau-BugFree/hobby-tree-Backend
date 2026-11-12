"""Launcher tests use only synthetic secrets and never start the app or a provider."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("coach_launcher", Path(__file__).with_name("coach-entrypoint.py"))
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)

FAKE_KEY = "sk-ant-" + "unit-test-not-a-real-key-" * 3
FAKE_TOKEN = "local-test-service-token-" * 3


class CoachLauncherTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.secret("coach_service_token", FAKE_TOKEN)

    def secret(self, name, text):
        path = self.root / name
        path.write_text(text, encoding="ascii")
        path.chmod(0o400)
        return path

    def test_offline_discards_inherited_key_without_reading_paid_secret(self):
        env = {"ANTHROPIC_API_KEY": FAKE_KEY}
        launcher.prepare_environment(env, self.root)
        self.assertEqual(env["ANTHROPIC_API_KEY"], "")
        self.assertEqual(env["COACH_BACKEND_TOKEN"], FAKE_TOKEN)
        self.assertFalse((self.root / "anthropic_api_key").exists())

    def test_paid_mode_selects_haiku_and_bounds_calls(self):
        self.secret("anthropic_api_key", FAKE_KEY + "\n")
        env = {"COACH_LLM_ENABLED": "true", "CLAUDE_MODEL": "other",
               "CLAUDE_MAX_RETRIES": "10", "ANTHROPIC_BASE_URL": "https://example.invalid"}
        launcher.prepare_environment(env, self.root)
        self.assertEqual(env["ANTHROPIC_API_KEY"], FAKE_KEY)
        self.assertEqual(env["CLAUDE_MODEL"], "claude-haiku-5-5")
        self.assertEqual(env["CLAUDE_MAX_TOKENS"], "4096")
        self.assertEqual(env["CLAUDE_TIMEOUT_SECONDS"], "10")
        self.assertEqual(env["CLAUDE_MAX_RETRIES"], "0")
        self.assertNotIn("ANTHROPIC_BASE_URL", env)

    def test_explicit_false_is_offline(self):
        self.secret("anthropic_api_key", FAKE_KEY)
        env = {"COACH_LLM_ENABLED": "false"}
        launcher.prepare_environment(env, self.root)
        self.assertEqual(env["ANTHROPIC_API_KEY"], "")

    def test_invalid_flag_fails(self):
        for flag in ["TRUE", "", "yes", "1"]:
            with self.subTest(flag=flag), self.assertRaisesRegex(ValueError, "COACH_LLM_ENABLED"):
                launcher.prepare_environment({"COACH_LLM_ENABLED": flag}, self.root)

    def test_missing_key_fails_instead_of_starting_offline(self):
        with self.assertRaisesRegex(ValueError, "owner-only regular file"):
            launcher.prepare_environment({"COACH_LLM_ENABLED": "true"}, self.root)

    def test_invalid_key_is_never_in_error(self):
        for invalid in ["", "private-invalid-key", FAKE_KEY + "\nEXTRA=1", "x" * 4097]:
            path = self.root / "anthropic_api_key"
            if path.exists():
                path.chmod(0o600)
            self.secret("anthropic_api_key", invalid)
            with self.assertRaises(ValueError) as caught:
                launcher.prepare_environment({"COACH_LLM_ENABLED": "true"}, self.root)
            self.assertNotIn("private-invalid-key", str(caught.exception))
            self.assertNotIn(FAKE_KEY, str(caught.exception))

    def test_symlink_directory_and_public_file_are_rejected(self):
        key = self.secret("source", FAKE_KEY)
        link = self.root / "anthropic_api_key"
        link.symlink_to(key)
        with self.assertRaises(ValueError):
            launcher.prepare_environment({"COACH_LLM_ENABLED": "true"}, self.root)
        link.unlink()
        link.mkdir()
        with self.assertRaises(ValueError):
            launcher.prepare_environment({"COACH_LLM_ENABLED": "true"}, self.root)
        link.rmdir()
        link = self.secret("anthropic_api_key", FAKE_KEY)
        link.chmod(0o644)
        with self.assertRaises(ValueError):
            launcher.prepare_environment({"COACH_LLM_ENABLED": "true"}, self.root)

    def test_invalid_token_is_not_replaced_or_disclosed(self):
        path = self.root / "coach_service_token"
        path.chmod(0o600)
        self.secret("coach_service_token", "short-secret")
        with self.assertRaisesRegex(ValueError, "invalid format") as caught:
            launcher.prepare_environment({}, self.root)
        self.assertNotIn("short-secret", str(caught.exception))
        self.assertEqual(path.read_text(), "short-secret")

    def test_startup_failure_does_not_exec(self):
        with patch.object(launcher, "prepare_environment", side_effect=ValueError("invalid configuration")), \
                patch.object(launcher.os, "execvp") as execute:
            with self.assertRaises(SystemExit):
                launcher.main()
            execute.assert_not_called()

    def test_exec_arguments_do_not_include_secrets(self):
        with patch.object(launcher, "prepare_environment"), patch.object(launcher.os, "execvp") as execute:
            launcher.main()
            self.assertIn("--no-access-log", execute.call_args.args[1])
            self.assertNotIn(FAKE_KEY, repr(execute.call_args))


if __name__ == "__main__":
    unittest.main()
