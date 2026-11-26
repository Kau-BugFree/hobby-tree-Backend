"""Exercise Kakao Local API configuration with synthetic values and no network access."""

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
    "kakao_fetch", Path(__file__).resolve().parents[1] / "fetch-ec2-secrets.py")
FETCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FETCH)
VALIDATOR = FETCH.VALIDATOR
SYNTHETIC_KEY = "a" * 32
PROPERTIES = {"location.kakao-rest-api-key": SYNTHETIC_KEY}


def properties(**changes):
    """Produce literal test properties, never a live Kakao credential."""
    return "".join(key + "=" + value + "\n" for key, value in dict(PROPERTIES, **changes).items())


class KakaoPropertiesTests(unittest.TestCase):
    """Check the strict host-file contract and environment-override boundary."""

    def test_valid_properties_round_trip(self):
        parsed = VALIDATOR.validate_kakao_properties(properties())
        self.assertEqual(parsed, PROPERTIES)

    def test_malformed_or_extra_properties_fail_without_echoing_values(self):
        valid = properties()
        cases = ["", valid * 2, valid + "spring.flyway.enabled=true\n",
                 valid.replace("\n", "\r\n"), valid + "\x00", valid + "#한글",
                 valid.replace("location.kakao-rest-api-key=", "location.kakao-rest-api-key:"),
                 valid.replace("location.kakao-rest-api-key=" + SYNTHETIC_KEY + "\n", ""),
                 valid.replace(SYNTHETIC_KEY, SYNTHETIC_KEY + " ")]
        for index, contents in enumerate(cases):
            with self.subTest(case=index), self.assertRaises(ValueError) as error:
                VALIDATOR.validate_kakao_properties(contents)
            self.assertNotIn(SYNTHETIC_KEY, str(error.exception))

    def test_rest_api_key_charset_and_length_bounds(self):
        bad_keys = ["short", "A" * 32, "x" * 31, "x" * 33, "has-dash" + "0" * 24,
                    "0123456789abcdef0123456789abcdeg", ""]
        for value in bad_keys:
            with self.subTest(value=value), self.assertRaises(ValueError):
                VALIDATOR.validate_kakao_properties(properties(**{"location.kakao-rest-api-key": value}))

    def test_environment_cannot_override_the_protected_file_including_aliases(self):
        settings = {"KAKAO_APP_ID": "1", "GOOGLE_CLIENT_ID": "ci.apps.googleusercontent.com",
                    "REDIS_HOST": "redis", "REDIS_PORT": "6379"}
        VALIDATOR.validate_auth_settings(settings, True)
        for key in ("KAKAO_REST_API_KEY", "LOCATION_KAKAO_REST_API_KEY",
                    "location.kakao-rest-api-key", "location_kakao_rest_api_key"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                VALIDATOR.validate_auth_settings(dict(settings, **{key: "sentinel"}), True)


@unittest.skipUnless(os.name == "posix" and os.geteuid() == 0, "Linux root permission fixture required")
class KakaoFileTests(unittest.TestCase):
    """Use only disposable /run/hobbytree paths; all AWS reads are mocked."""

    def setUp(self):
        """Create a root-owned isolated host source and synthetic app credentials."""
        Path("/run/hobbytree").mkdir(mode=0o755, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="kakao-config-test-", dir="/run/hobbytree")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "kakao.properties"
        self.output = self.root / "app"
        self.credentials = {"DB_USERNAME": "test_app", "DB_PASSWORD": "test-password",
                            "JWT_SECRET": base64.b64encode(b"test-only" * 8).decode(),
                            "REDIS_PASSWORD": "test_only_" + "r" * 32}

    def install_source(self, contents=None):
        """Write a synthetic source with the production ownership/mode contract."""
        self.source.write_text(properties() if contents is None else contents, encoding="utf-8")
        self.source.chmod(0o600)

    def test_optional_source_preserves_required_four_files(self):
        """An absent Kakao source does not add the runtime Secret file."""
        self.assertEqual(FETCH.read_kakao_credentials(self.source, self.output), {})
        FETCH.write_credentials(str(self.output), self.credentials)
        VALIDATOR.validate_secret_files(self.output)
        self.assertEqual(len(list(self.output.iterdir())), 4)

    def test_protected_file_is_repeatable_and_turned_into_one_secret(self):
        """Preserve the host source, turn it into one file and enforce permissions."""
        self.install_source()
        for _ in range(2):
            values = dict(self.credentials, **FETCH.read_kakao_credentials(self.source, self.output))
            FETCH.write_credentials(str(self.output), values)
            VALIDATOR.validate_secret_files(self.output)
            info = (self.output / "KAKAO_REST_API_KEY").stat()
            self.assertEqual((info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)), (10001, 10001, 0o400))
            self.assertEqual((self.output / "KAKAO_REST_API_KEY").read_text(), SYNTHETIC_KEY)
        (self.output / "KAKAO_REST_API_KEY").chmod(0o644)
        with self.assertRaises(ValueError):
            VALIDATOR.validate_secret_files(self.output)

    def test_removed_source_never_silently_reuses_stale_configuration(self):
        """Source removal requires explicit stopped-service rollback, including stale symlinks."""
        self.output.mkdir()
        target = self.output / "KAKAO_REST_API_KEY"
        target.write_text(SYNTHETIC_KEY)
        with self.assertRaises(ValueError):
            FETCH.read_kakao_credentials(self.source, self.output)
        target.unlink()
        target.symlink_to(self.root / "missing")
        with self.assertRaises(ValueError):
            FETCH.read_kakao_credentials(self.source, self.output)

    def test_unprotected_source_and_symlink_parents_fail(self):
        """Reject source contents or ownership that could change outside root control."""
        self.install_source()
        for mode in (0o644, 0o400, 0o666):
            self.source.chmod(mode)
            with self.assertRaises(ValueError):
                FETCH.read_kakao_credentials(self.source, self.output)
        self.source.chmod(0o600)
        os.chown(self.source, 10001, 10001)
        with self.assertRaises(ValueError):
            FETCH.read_kakao_credentials(self.source, self.output)
        os.chown(self.source, 0, 0)
        link = self.root / "linked"
        link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError):
            FETCH.read_kakao_credentials(link / "kakao.properties", self.output)
        self.root.chmod(0o777)
        with self.assertRaises(ValueError):
            FETCH.read_kakao_credentials(self.source, self.output)
        self.root.chmod(0o700)

    def test_invalid_contents_fail_before_output_mutation(self):
        """Validate both the host reader and writer, including the wrong-uid boundary."""
        for contents in (properties().replace("\n", "\r\n"), properties() + "x=y\n", "", "x" * 4097):
            self.install_source(contents)
            with self.assertRaises(ValueError):
                FETCH.read_kakao_credentials(self.source, self.output)
        with self.assertRaises(ValueError):
            FETCH.write_credentials(str(self.output), {"KAKAO_REST_API_KEY": "invalid"})
        with self.assertRaises(ValueError):
            FETCH.write_credentials(str(self.output), {"KAKAO_REST_API_KEY": SYNTHETIC_KEY}, uid=999)
        self.assertFalse(self.output.exists())

    def test_atomic_replace_failure_preserves_old_kakao_file_and_directory_permissions(self):
        """A failed replacement leaves the old complete file and no temporary fragments."""
        FETCH.write_credentials(str(self.output), {"KAKAO_REST_API_KEY": SYNTHETIC_KEY})
        changed = {"KAKAO_REST_API_KEY": "b" * 32}
        with patch.object(FETCH.os, "replace", side_effect=OSError("synthetic failure")):
            with self.assertRaises(OSError):
                FETCH.write_credentials(str(self.output), changed)
        self.assertEqual((self.output / "KAKAO_REST_API_KEY").read_text(), SYNTHETIC_KEY)
        self.assertEqual({p.name for p in self.output.iterdir()}, {"KAKAO_REST_API_KEY"})
        info = self.output.stat()
        self.assertEqual((info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)), (10001, 10001, 0o500))

    def test_main_rehydrates_kakao_config_and_redacts_validation_failure_before_aws(self):
        """Drive the actual preparation entrypoint with mocked AWS and synthetic files."""
        self.install_source()
        arguments = ["fetch", "--secret-id", "test", "--expected-username", "test_app",
                     "--auth-secret-id", "test-auth", "--output-dir", str(self.output),
                     "--redis-output-dir", str(self.root / "redis")]
        output = io.StringIO()
        with patch.object(FETCH, "AI_SOURCE", self.root / "no-ai-source-here.properties"), \
             patch.object(FETCH, "NAVER_SOURCE", self.root / "no-naver-source-here.properties"), \
             patch.object(FETCH, "KAKAO_SOURCE", self.source), patch.object(FETCH.sys, "argv", arguments), \
             patch.object(FETCH, "read_secret", side_effect=[
                 {"username": "test_app", "password": "test-password"}, self.credentials]) as aws, \
             redirect_stdout(output), redirect_stderr(output):
            self.assertEqual(FETCH.main(), 0)
            VALIDATOR.validate_secret_files(self.output, self.root / "redis")
            self.assertEqual((self.output / "KAKAO_REST_API_KEY").read_text(), SYNTHETIC_KEY)
            self.install_source(properties(**{"location.kakao-rest-api-key": "short"}))
            self.assertEqual(FETCH.main(), 1)
            self.assertEqual(aws.call_count, 2)
        self.assertNotIn(SYNTHETIC_KEY, output.getvalue())
        self.assertNotIn("test-password", output.getvalue())


if __name__ == "__main__":
    unittest.main()
