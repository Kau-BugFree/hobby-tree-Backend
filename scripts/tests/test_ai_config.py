"""Exercise AI configuration with synthetic values and no AWS/network access."""

import base64
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "ai_fetch", Path(__file__).resolve().parents[1] / "fetch-ec2-secrets.py")
FETCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FETCH)
VALIDATOR = FETCH.VALIDATOR
SYNTHETIC_TOKEN = "test_only_" + "x" * 55
PROPERTIES = {
    "recommendation.ai.enabled": "true",
    "recommendation.ai.base-url": "http://10.0.0.12:8000",
    "recommendation.ai.connect-timeout": "2s",
    "recommendation.ai.request-timeout": "10s",
    "coach.ai.enabled": "true",
    "coach.ai.base-url": "http://10.0.0.12:8003",
    "coach.ai.service-token": SYNTHETIC_TOKEN,
    "coach.ai.connect-timeout": "2s",
    "coach.ai.request-timeout": "45s",
}


def properties(**changes):
    """Produce literal test properties, never live connection credentials."""
    return "".join(key + "=" + value + "\n" for key, value in dict(PROPERTIES, **changes).items())


class AiPropertiesTests(unittest.TestCase):
    """Check the strict file contract and environment precedence boundary."""

    def test_enabled_disabled_and_bounded_durations(self):
        """Allow explicit disable and both supported duration units."""
        VALIDATOR.validate_ai_properties(properties())
        VALIDATOR.validate_ai_properties(properties(**{
            "coach.ai.enabled": "false", "recommendation.ai.enabled": "false",
            "coach.ai.request-timeout": "120000ms", "recommendation.ai.request-timeout": "30s"}))

    def test_malformed_or_extra_properties_fail_without_echoing_values(self):
        """Reject syntax that Spring might interpret differently or as arbitrary settings."""
        valid = properties()
        cases = ["", valid * 2, valid + "spring.flyway.enabled=true\n", valid.replace("=true", "=TRUE"),
                 valid.replace("=true", "= true"), valid.replace("=true", "=true\\"),
                 valid.replace("=true", "=${FLAG}"), valid.replace("\n", "\r\n"),
                 valid + "\x00", valid + "#한글", valid + "#" + "x" * 4096,
                 valid.replace("coach.ai.enabled=true\n", ""), valid.replace("coach.ai.enabled=", "coach.ai.enabled:")]
        for index, contents in enumerate(cases):
            with self.subTest(case=index), self.assertRaises(ValueError) as error:
                VALIDATOR.validate_ai_properties(contents)
            self.assertNotIn(SYNTHETIC_TOKEN, str(error.exception))

    def test_unsafe_origins_tokens_and_timeouts_fail(self):
        """Block public/metadata/credential origins and out-of-contract client settings."""
        bad_urls = ["http://127.0.0.1:8003", "http://169.254.169.254:8003", "http://8.8.8.8:8003",
                    "http://100.64.0.1:8003", "http://[::1]:8003", "http://ai.internal:8003",
                    "http://10.0.0.12:8000", "https://10.0.0.12:8003", "http://user@10.0.0.12:8003",
                    "http://10.0.0.12:8003/path", "http://10.0.0.12:8003?x", "http://10.0.0.12:8003#x",
                    "http://010.0.0.12:8003", "http://999.0.0.12:8003"]
        cases = [("coach.ai.base-url", value) for value in bad_urls]
        cases += [("coach.ai.service-token", value) for value in ("", "short", "x" * 129, "!" * 64)]
        cases += [("coach.ai.request-timeout", value) for value in ("0s", "-1s", "121s", "1s", "PT2S", "1.5s")]
        cases += [("recommendation.ai.request-timeout", "31s"), ("coach.ai.connect-timeout", "46s")]
        for index, (key, value) in enumerate(cases):
            with self.subTest(case=index), self.assertRaises(ValueError):
                VALIDATOR.validate_ai_properties(properties(**{key: value}))

    def test_environment_cannot_override_file_or_absence(self):
        """Check Spring relaxed aliases without affecting unrelated variables."""
        settings = {"SPRING_PROFILES_ACTIVE": "ec2", "SPRING_FLYWAY_ENABLED": "false",
                    "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate"}
        VALIDATOR.validate_runtime(settings)
        for key in ("COACH_AI_SERVICE_TOKEN", "COACH_AI_SERVICETOKEN", "RECOMMENDATION_AI_ENABLED",
                    "coach.ai.enabled", "recommendation.ai.base-url"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                VALIDATOR.validate_runtime(dict(settings, **{key: "sentinel"}))


@unittest.skipUnless(os.name == "posix" and os.geteuid() == 0, "Linux root permission fixture required")
class AiFileTests(unittest.TestCase):
    """Use only disposable /run/hobbytree paths; all AWS reads are mocked."""

    def setUp(self):
        """Create a root-owned isolated host source and synthetic app credentials."""
        Path("/run/hobbytree").mkdir(mode=0o755, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="ai-config-test-", dir="/run/hobbytree")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "ai.properties"
        self.output = self.root / "app"
        self.credentials = {"DB_USERNAME": "test_app", "DB_PASSWORD": "test-password",
                            "JWT_SECRET": base64.b64encode(b"test-only" * 8).decode(),
                            "REDIS_PASSWORD": "test_only_" + "r" * 32}

    def install_source(self, contents=None):
        """Write a synthetic source with the production ownership/mode contract."""
        self.source.write_text(properties() if contents is None else contents, encoding="utf-8")
        self.source.chmod(0o600)

    def test_optional_source_preserves_legacy_four_files(self):
        """An absent AI source does not enable either client or add a runtime file."""
        self.assertEqual(FETCH.read_ai_credentials(self.source, self.output), {})
        FETCH.write_credentials(str(self.output), self.credentials)
        VALIDATOR.validate_secret_files(self.output)
        self.assertEqual(len(list(self.output.iterdir())), 4)

    def test_protected_file_is_repeatable_and_validated_with_existing_secrets(self):
        """Preserve the host source, import exactly one complete file and enforce permissions."""
        self.install_source()
        for _ in range(2):
            values = dict(self.credentials, **FETCH.read_ai_credentials(self.source, self.output))
            FETCH.write_credentials(str(self.output), values)
            VALIDATOR.validate_secret_files(self.output)
            info = (self.output / "ai.properties").stat()
            self.assertEqual((info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)), (10001, 10001, 0o400))
        self.assertEqual((self.output / "ai.properties").read_text(), self.source.read_text())
        (self.output / "ai.properties").chmod(0o644)
        with self.assertRaises(ValueError):
            VALIDATOR.validate_secret_files(self.output)

    def test_removed_source_never_silently_reuses_stale_configuration(self):
        """Source removal requires explicit stopped-service rollback, including stale symlinks."""
        self.output.mkdir()
        target = self.output / "ai.properties"
        target.write_text(properties())
        with self.assertRaises(ValueError):
            FETCH.read_ai_credentials(self.source, self.output)
        target.unlink()
        target.symlink_to(self.root / "missing")
        with self.assertRaises(ValueError):
            FETCH.read_ai_credentials(self.source, self.output)

    def test_unprotected_source_and_symlink_parents_fail(self):
        """Reject source contents or ownership that could change outside root control."""
        self.install_source()
        for mode in (0o644, 0o400, 0o666):
            self.source.chmod(mode)
            with self.assertRaises(ValueError):
                FETCH.read_ai_credentials(self.source, self.output)
        self.source.chmod(0o600)
        os.chown(self.source, 10001, 10001)
        with self.assertRaises(ValueError):
            FETCH.read_ai_credentials(self.source, self.output)
        os.chown(self.source, 0, 0)
        link = self.root / "linked"
        link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError):
            FETCH.read_ai_credentials(link / "ai.properties", self.output)
        self.root.chmod(0o777)
        with self.assertRaises(ValueError):
            FETCH.read_ai_credentials(self.source, self.output)
        self.root.chmod(0o700)

    def test_invalid_contents_fail_before_output_mutation(self):
        """Validate both the host reader and writer, including the Redis destination boundary."""
        for contents in (properties().replace("\n", "\r\n"), properties() + "x=y\n", "", "x" * 4097):
            self.install_source(contents)
            with self.assertRaises(ValueError):
                FETCH.read_ai_credentials(self.source, self.output)
        with self.assertRaises(ValueError):
            FETCH.write_credentials(str(self.output), {"ai.properties": "invalid"})
        with self.assertRaises(ValueError):
            FETCH.write_credentials(str(self.output), {"ai.properties": properties()}, uid=999)
        self.assertFalse(self.output.exists())

    def test_atomic_replace_failure_preserves_old_ai_file_and_directory_permissions(self):
        """A failed replacement leaves the old complete file and no temporary fragments."""
        FETCH.write_credentials(str(self.output), {"ai.properties": properties()})
        changed = properties(**{"coach.ai.enabled": "false"})
        with patch.object(FETCH.os, "replace", side_effect=OSError("synthetic failure")):
            with self.assertRaises(OSError):
                FETCH.write_credentials(str(self.output), {"ai.properties": changed})
        self.assertEqual((self.output / "ai.properties").read_text(), properties())
        self.assertEqual({p.name for p in self.output.iterdir()}, {"ai.properties"})
        info = self.output.stat()
        self.assertEqual((info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)), (10001, 10001, 0o500))

    def test_main_rehydrates_config_and_redacts_validation_failure_before_aws(self):
        """Drive the actual preparation entrypoint with mocked AWS and synthetic files."""
        self.install_source()
        arguments = ["fetch", "--secret-id", "test", "--expected-username", "test_app",
                     "--auth-secret-id", "test-auth", "--output-dir", str(self.output),
                     "--redis-output-dir", str(self.root / "redis")]
        output = io.StringIO()
        with patch.object(FETCH, "AI_SOURCE", self.source), patch.object(FETCH.sys, "argv", arguments), \
             patch.object(FETCH, "read_secret", side_effect=[
                 {"username": "test_app", "password": "test-password"}, self.credentials]) as aws, \
             redirect_stdout(output), redirect_stderr(output):
            self.assertEqual(FETCH.main(), 0)
            VALIDATOR.validate_secret_files(self.output, self.root / "redis")
            self.install_source(properties() + "bad=" + SYNTHETIC_TOKEN)
            self.assertEqual(FETCH.main(), 1)
            self.assertEqual(aws.call_count, 2)
        self.assertNotIn(SYNTHETIC_TOKEN, output.getvalue())
        self.assertNotIn("test-password", output.getvalue())


if __name__ == "__main__":
    unittest.main()
