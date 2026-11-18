"""Exercise Naver local-search configuration with synthetic values and no network access."""

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
    "naver_fetch", Path(__file__).resolve().parents[1] / "fetch-ec2-secrets.py")
FETCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FETCH)
VALIDATOR = FETCH.VALIDATOR
SYNTHETIC_SECRET = "testonlynaversecretvalue0123456789"
PROPERTIES = {
    "naver.local-search.client-id": "testonlyclientid1",
    "naver.local-search.client-secret": SYNTHETIC_SECRET,
}


def properties(**changes):
    """Produce literal test properties, never a live Naver credential."""
    return "".join(key + "=" + value + "\n" for key, value in dict(PROPERTIES, **changes).items())


class NaverPropertiesTests(unittest.TestCase):
    """Check the strict host-file contract and environment-override boundary."""

    def test_valid_properties_round_trip(self):
        parsed = VALIDATOR.validate_naver_properties(properties())
        self.assertEqual(parsed, PROPERTIES)

    def test_malformed_or_extra_properties_fail_without_echoing_values(self):
        valid = properties()
        cases = ["", valid * 2, valid + "spring.flyway.enabled=true\n",
                 valid.replace("\n", "\r\n"), valid + "\x00", valid + "#한글",
                 valid.replace("naver.local-search.client-id=", "naver.local-search.client-id:"),
                 valid.replace("naver.local-search.client-id=testonlyclientid1\n", ""),
                 valid.replace(SYNTHETIC_SECRET, SYNTHETIC_SECRET + " ")]
        for index, contents in enumerate(cases):
            with self.subTest(case=index), self.assertRaises(ValueError) as error:
                VALIDATOR.validate_naver_properties(contents)
            self.assertNotIn(SYNTHETIC_SECRET, str(error.exception))

    def test_client_id_and_secret_charset_and_length_bounds(self):
        bad_ids = ["short", "x" * 65, "has-dash", "has_underscore", "has space", ""]
        bad_secrets = ["x" * 19, "x" * 129, "has-dash" + "x" * 20, "has_underscore" + "x" * 20, ""]
        for value in bad_ids:
            with self.subTest(client_id=value), self.assertRaises(ValueError):
                VALIDATOR.validate_naver_properties(properties(**{"naver.local-search.client-id": value}))
        for value in bad_secrets:
            with self.subTest(client_secret=value), self.assertRaises(ValueError):
                VALIDATOR.validate_naver_properties(properties(**{"naver.local-search.client-secret": value}))

    def test_environment_cannot_override_the_protected_file_including_aliases(self):
        settings = {"KAKAO_APP_ID": "1", "GOOGLE_CLIENT_ID": "ci.apps.googleusercontent.com",
                    "REDIS_HOST": "redis", "REDIS_PORT": "6379"}
        VALIDATOR.validate_auth_settings(settings, True)
        for key in ("NAVER_LOCAL_SEARCH_CLIENT_ID", "NAVER_LOCAL_SEARCH_CLIENT_SECRET",
                    "NAVER_LOCALSEARCH_CLIENTSECRET", "naver.local-search.client-id",
                    "naver_local_search_client_secret"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                VALIDATOR.validate_auth_settings(dict(settings, **{key: "sentinel"}), True)


@unittest.skipUnless(os.name == "posix" and os.geteuid() == 0, "Linux root permission fixture required")
class NaverFileTests(unittest.TestCase):
    """Use only disposable /run/hobbytree paths; all AWS reads are mocked."""

    def setUp(self):
        """Create a root-owned isolated host source and synthetic app credentials."""
        Path("/run/hobbytree").mkdir(mode=0o755, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="naver-config-test-", dir="/run/hobbytree")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "naver.properties"
        self.output = self.root / "app"
        self.credentials = {"DB_USERNAME": "test_app", "DB_PASSWORD": "test-password",
                            "JWT_SECRET": base64.b64encode(b"test-only" * 8).decode(),
                            "REDIS_PASSWORD": "test_only_" + "r" * 32}
        self.naver_files = {"NAVER_LOCAL_SEARCH_CLIENT_ID": PROPERTIES["naver.local-search.client-id"],
                            "NAVER_LOCAL_SEARCH_CLIENT_SECRET": PROPERTIES["naver.local-search.client-secret"]}

    def install_source(self, contents=None):
        """Write a synthetic source with the production ownership/mode contract."""
        self.source.write_text(properties() if contents is None else contents, encoding="utf-8")
        self.source.chmod(0o600)

    def test_optional_source_preserves_required_four_files(self):
        """An absent Naver source does not add either runtime Secret file."""
        self.assertEqual(FETCH.read_naver_credentials(self.source, self.output), {})
        FETCH.write_credentials(str(self.output), self.credentials)
        VALIDATOR.validate_secret_files(self.output)
        self.assertEqual(len(list(self.output.iterdir())), 4)

    def test_protected_file_is_repeatable_and_split_into_two_secrets(self):
        """Preserve the host source, split it into two files and enforce permissions."""
        self.install_source()
        for _ in range(2):
            values = dict(self.credentials, **FETCH.read_naver_credentials(self.source, self.output))
            FETCH.write_credentials(str(self.output), values)
            VALIDATOR.validate_secret_files(self.output)
            for name in self.naver_files:
                info = (self.output / name).stat()
                self.assertEqual((info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)), (10001, 10001, 0o400))
                self.assertEqual((self.output / name).read_text(), self.naver_files[name])
        (self.output / "NAVER_LOCAL_SEARCH_CLIENT_ID").chmod(0o644)
        with self.assertRaises(ValueError):
            VALIDATOR.validate_secret_files(self.output)

    def test_partial_pair_is_rejected(self):
        """A client ID without its secret (or vice versa) must fail validation."""
        FETCH.write_credentials(str(self.output), self.credentials)
        FETCH.write_credentials(str(self.output), dict(self.credentials, **self.naver_files))
        one = {"NAVER_LOCAL_SEARCH_CLIENT_ID": self.naver_files["NAVER_LOCAL_SEARCH_CLIENT_ID"]}
        (self.output / "NAVER_LOCAL_SEARCH_CLIENT_SECRET").unlink()
        with self.assertRaises(ValueError):
            VALIDATOR.validate_secret_files(self.output)
        with self.assertRaises(ValueError):
            FETCH.write_credentials(str(self.output), dict(self.credentials, **one))

    def test_pair_replace_failure_restores_the_previous_pair_not_a_mismatch(self):
        """A failure replacing only the secret must not leave a new ID with the old secret."""
        FETCH.write_credentials(str(self.output), dict(self.naver_files))
        changed = {"NAVER_LOCAL_SEARCH_CLIENT_ID": "testonlyclientid2",
                   "NAVER_LOCAL_SEARCH_CLIENT_SECRET": "testonlynaversecretvalue9876543210"}
        real_replace, calls = FETCH.os.replace, {"n": 0}

        def flaky_replace(src, dst):
            calls["n"] += 1
            if calls["n"] == 2:
                raise OSError("synthetic failure")
            return real_replace(src, dst)

        with patch.object(FETCH.os, "replace", side_effect=flaky_replace):
            with self.assertRaises(OSError):
                FETCH.write_credentials(str(self.output), changed)
        self.assertEqual((self.output / "NAVER_LOCAL_SEARCH_CLIENT_ID").read_text(),
                         self.naver_files["NAVER_LOCAL_SEARCH_CLIENT_ID"])
        self.assertEqual((self.output / "NAVER_LOCAL_SEARCH_CLIENT_SECRET").read_text(),
                         self.naver_files["NAVER_LOCAL_SEARCH_CLIENT_SECRET"])

    def test_pair_replace_failure_on_first_install_removes_the_orphan_not_a_mismatch(self):
        """With no previous pair, a mid-pair failure must not leave a lone new ID behind."""
        real_replace, calls = FETCH.os.replace, {"n": 0}

        def flaky_replace(src, dst):
            calls["n"] += 1
            if calls["n"] == 2:
                raise OSError("synthetic failure")
            return real_replace(src, dst)

        with patch.object(FETCH.os, "replace", side_effect=flaky_replace):
            with self.assertRaises(OSError):
                FETCH.write_credentials(str(self.output), dict(self.naver_files))
        self.assertEqual(list(self.output.iterdir()), [])

    def test_removed_source_never_silently_reuses_stale_configuration(self):
        """Source removal requires explicit stopped-service rollback, including stale symlinks."""
        self.output.mkdir()
        target = self.output / "NAVER_LOCAL_SEARCH_CLIENT_ID"
        target.write_text(PROPERTIES["naver.local-search.client-id"])
        with self.assertRaises(ValueError):
            FETCH.read_naver_credentials(self.source, self.output)
        target.unlink()
        target.symlink_to(self.root / "missing")
        with self.assertRaises(ValueError):
            FETCH.read_naver_credentials(self.source, self.output)

    def test_unprotected_source_and_symlink_parents_fail(self):
        """Reject source contents or ownership that could change outside root control."""
        self.install_source()
        for mode in (0o644, 0o400, 0o666):
            self.source.chmod(mode)
            with self.assertRaises(ValueError):
                FETCH.read_naver_credentials(self.source, self.output)
        self.source.chmod(0o600)
        os.chown(self.source, 10001, 10001)
        with self.assertRaises(ValueError):
            FETCH.read_naver_credentials(self.source, self.output)
        os.chown(self.source, 0, 0)
        link = self.root / "linked"
        link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError):
            FETCH.read_naver_credentials(link / "naver.properties", self.output)
        self.root.chmod(0o777)
        with self.assertRaises(ValueError):
            FETCH.read_naver_credentials(self.source, self.output)
        self.root.chmod(0o700)

    def test_invalid_contents_fail_before_output_mutation(self):
        """Validate both the host reader and writer, including the wrong-uid boundary."""
        for contents in (properties().replace("\n", "\r\n"), properties() + "x=y\n", "", "x" * 4097):
            self.install_source(contents)
            with self.assertRaises(ValueError):
                FETCH.read_naver_credentials(self.source, self.output)
        with self.assertRaises(ValueError):
            FETCH.write_credentials(str(self.output), {"NAVER_LOCAL_SEARCH_CLIENT_ID": "invalid"})
        with self.assertRaises(ValueError):
            FETCH.write_credentials(str(self.output), dict(self.naver_files), uid=999)
        self.assertFalse(self.output.exists())

    def test_atomic_replace_failure_preserves_old_naver_files_and_directory_permissions(self):
        """A failed replacement leaves the old complete files and no temporary fragments."""
        FETCH.write_credentials(str(self.output), dict(self.naver_files))
        changed = {"NAVER_LOCAL_SEARCH_CLIENT_ID": "testonlyclientid2",
                   "NAVER_LOCAL_SEARCH_CLIENT_SECRET": self.naver_files["NAVER_LOCAL_SEARCH_CLIENT_SECRET"]}
        with patch.object(FETCH.os, "replace", side_effect=OSError("synthetic failure")):
            with self.assertRaises(OSError):
                FETCH.write_credentials(str(self.output), changed)
        self.assertEqual((self.output / "NAVER_LOCAL_SEARCH_CLIENT_ID").read_text(),
                         self.naver_files["NAVER_LOCAL_SEARCH_CLIENT_ID"])
        self.assertEqual({p.name for p in self.output.iterdir()}, set(self.naver_files))
        info = self.output.stat()
        self.assertEqual((info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode)), (10001, 10001, 0o500))

    def test_main_rehydrates_naver_config_and_redacts_validation_failure_before_aws(self):
        """Drive the actual preparation entrypoint with mocked AWS and synthetic files."""
        self.install_source()
        arguments = ["fetch", "--secret-id", "test", "--expected-username", "test_app",
                     "--auth-secret-id", "test-auth", "--output-dir", str(self.output),
                     "--redis-output-dir", str(self.root / "redis")]
        output = io.StringIO()
        with patch.object(FETCH, "AI_SOURCE", self.root / "no-ai-source-here.properties"), \
             patch.object(FETCH, "NAVER_SOURCE", self.source), patch.object(FETCH.sys, "argv", arguments), \
             patch.object(FETCH, "read_secret", side_effect=[
                 {"username": "test_app", "password": "test-password"}, self.credentials]) as aws, \
             redirect_stdout(output), redirect_stderr(output):
            self.assertEqual(FETCH.main(), 0)
            VALIDATOR.validate_secret_files(self.output, self.root / "redis")
            for name, value in self.naver_files.items():
                self.assertEqual((self.output / name).read_text(), value)
            self.install_source(properties(**{"naver.local-search.client-secret": "x" * 19}))
            self.assertEqual(FETCH.main(), 1)
            self.assertEqual(aws.call_count, 2)
        self.assertNotIn(SYNTHETIC_SECRET, output.getvalue())
        self.assertNotIn("test-password", output.getvalue())


if __name__ == "__main__":
    unittest.main()
