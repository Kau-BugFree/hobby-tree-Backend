import importlib.util
import io
import json
from contextlib import redirect_stderr
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "validator", Path(__file__).resolve().parents[1] / "validate-ec2-config.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
URL = "jdbc:postgresql://postgres:5432/hobbytree_ci?sslmode=verify-full&sslrootcert=" + MODULE.CONTAINER_CA
CA = Path(__file__).parent / "fixtures" / "rds-ca-public.pem"


class DeploymentValidationTests(unittest.TestCase):
    def test_verify_full_with_mounted_ca_is_valid(self):
        MODULE.validate_url(URL)
        MODULE.validate_ca(CA)

    def test_insecure_modes_missing_duplicate_and_override_options_fail(self):
        bad = [URL.replace("verify-full", mode) for mode in
               ("disable", "allow", "prefer", "require", "verify-ca", "VERIFY-FULL", "")]
        bad += [URL.split("?")[0], URL.replace("sslmode=verify-full&", ""),
                URL.split("&")[0], URL.replace(MODULE.CONTAINER_CA, "/tmp/ca.pem"),
                URL + "&sslmode=verify-full", URL + "&ssl%6dode=disable",
                URL + "&ssl=false", URL + "&sslfactory=nonvalidating",
                URL + "&user=postgres", URL + "&password=do-not-log",
                URL.replace("postgres:5432/", "admin:do-not-log@postgres:5432/"),
                URL + "#fragment", URL.replace("sslmode", "sslmode%"),
                URL.replace(":5432", ":99999"), URL.replace("/hobbytree_ci?", "/db/extra?"),
                URL + "\n"]
        for value in bad:
            with self.subTest(case=bad.index(value)), self.assertRaises(ValueError):
                MODULE.validate_url(value)

    def test_ca_missing_empty_corrupt_or_expired_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.pem"
            for contents in (None, "", "not a certificate"):
                if contents is not None:
                    path.write_text(contents, encoding="utf-8")
                with self.assertRaises((ValueError, OSError)):
                    MODULE.validate_ca(path)
            with self.assertRaises(ValueError):
                MODULE.validate_ca(directory)
        with patch.object(MODULE.time, "time", return_value=7258118400):
            with self.assertRaises(ValueError):
                MODULE.validate_ca(CA)
        with patch.object(MODULE.time, "time", return_value=0):
            with self.assertRaises(ValueError):
                MODULE.validate_ca(CA)
        with patch.object(MODULE.os, "access", return_value=False):
            with self.assertRaises(ValueError):
                MODULE.validate_ca(CA)

    def test_runtime_cannot_enable_flyway_or_ddl_updates(self):
        valid = {"SPRING_PROFILES_ACTIVE": "ec2", "SPRING_FLYWAY_ENABLED": "false",
                 "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate"}
        MODULE.validate_runtime(valid)
        for key, value in (("SPRING_PROFILES_ACTIVE", "local"), ("SPRING_FLYWAY_ENABLED", "true"),
                           ("SPRING_JPA_HIBERNATE_DDL_AUTO", "update"),
                           ("SPRING_APPLICATION_JSON", "{}"), ("SPRING_DATASOURCE_URL", URL),
                           ("JAVA_TOOL_OPTIONS", "-Dspring.flyway.enabled=true"),
                           ("APP_SECRETS_PATH", "/tmp/other/")):
            with self.assertRaises(ValueError):
                MODULE.validate_runtime(dict(valid, **{key: value}))

    def test_env_file_is_literal_and_duplicate_entries_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "settings"
            path.write_text("# comment\nDB_URL='" + URL + "'\n", encoding="utf-8")
            self.assertEqual(MODULE.read_env_file(path)["DB_URL"], URL)
            for contents in ("DB_URL=x\nDB_URL=y", "DB_URL=${OTHER}", "DB_URL=", "BAD LINE"):
                path.write_text(contents, encoding="utf-8")
                with self.assertRaises(ValueError):
                    MODULE.read_env_file(path)

    def test_cli_failure_redacts_credentials_and_url(self):
        output = io.StringIO()
        bad = URL + "&password=do-not-log"
        settings = {"DB_URL": bad, "SPRING_PROFILES_ACTIVE": "ec2", "SPRING_FLYWAY_ENABLED": "false",
                    "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate"}
        with patch.object(MODULE.sys, "argv", ["validate", "--runtime"]), \
             patch.dict(MODULE.os.environ, settings, clear=True), redirect_stderr(output):
            self.assertEqual(MODULE.main(), 1)
        self.assertNotIn("do-not-log", output.getvalue())
        self.assertNotIn("jdbc:", output.getvalue())

    def test_systemd_retries_are_bounded_and_preflight_precedes_secrets(self):
        unit = (Path(__file__).resolve().parents[2] / "deploy" / "hobbytree-backend.service").read_text()
        self.assertIn("Restart=on-failure", unit)
        self.assertIn("RestartSec=20s", unit)
        self.assertIn("StartLimitIntervalSec=900", unit)
        self.assertIn("StartLimitBurst=5", unit)
        self.assertLess(unit.index("validate-ec2-config.py"), unit.index("fetch-ec2-secrets.py"))

    def test_base_image_lock_matches_dockerfile_and_ci(self):
        root = Path(__file__).resolve().parents[2]
        lock = json.loads((root / "deploy" / "base-image-lock.json").read_text())
        dockerfile = (root / "Dockerfile").read_text()
        compose = (root / "compose.ci.yml").read_text()
        for image in lock:
            self.assertRegex(image["IndexDigest"], r"^sha256:[0-9a-f]{64}$")
            target = (root / "compose.ec2.yml").read_text() if image["Repository"] == "library/redis" else (compose if image["Repository"] == "library/postgres" else dockerfile)
            self.assertIn(image["Tag"] + "@" + image["IndexDigest"], target)


if __name__ == "__main__":
    unittest.main()
