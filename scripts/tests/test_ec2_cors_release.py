"""Offline tests for the CORS setting and the existing release gate."""

import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("cors_release", ROOT / "scripts/deploy/ec2-cors-release.py")
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)


class CorsReleaseTests(unittest.TestCase):
    def test_allowlist_has_a_closed_default_and_requires_canonical_origins(self):
        """Accept explicit HTTPS and reject development, wildcard and injection values."""
        self.assertEqual(RELEASE.CORS.origins(""), [])
        self.assertEqual(RELEASE.CORS.origins("https://one.example,https://two.example:8443"),
                         ["https://one.example", "https://two.example:8443"])
        for value in ("*", "http://localhost:5173", "https://foo.localhost",
                      "https://*.example.com", "https://one.example/",
                      "https://one.example:443", "https://one.example:99999",
                      "https://127.0.0.1", "https://one.example?x=y",
                      "https://one.example\nSPRING_FLYWAY_ENABLED=true",
                      "https://one.example,https://one.example"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                RELEASE.CORS.origins(value)

    def test_cors_activation_and_rollback_write_only_the_reviewed_files(self):
        """Restore the previous bytes before the rollback Backend starts."""
        release = RELEASE.CorsRelease(None, ROOT, "1-1", "https://new.example")
        release.before = RELEASE.CORS.setting("https://previous.example")
        with patch.object(RELEASE.CORE.Deployment, "start_backend") as start, \
                patch.object(RELEASE.CORS, "persistent_value", return_value=release.before), \
                patch.object(RELEASE.CORS, "write") as write:
            release.start_backend()
            write.assert_called_once_with(release.desired)
            start.assert_called_once()
        release.record["rollback_stage"] = "start-previous-backend"
        with patch.object(RELEASE.CORE.Deployment, "start_backend") as start, \
                patch.object(RELEASE.CORS, "persistent_value", return_value=release.desired), \
                patch.object(RELEASE.CORS, "write") as write:
            release.start_backend()
            write.assert_called_once_with(release.before)
            start.assert_called_once()

    def test_runtime_drift_blocks_recovery_mutation(self):
        """Do not overwrite settings that belong to neither side of the release."""
        release = RELEASE.CorsRelease(None, ROOT, "1-1", "https://new.example")
        release.before = None
        release.record["rollback_stage"] = "start-previous-backend"
        with patch.object(RELEASE.CORE.Deployment, "start_backend") as start, \
                patch.object(RELEASE.CORS, "persistent_value", return_value=RELEASE.CORS.setting("https://changed.example")), \
                patch.object(RELEASE.CORS, "write") as write:
            with self.assertRaises(RELEASE.CORE.ReleaseError):
                release.start_backend()
            start.assert_not_called()
            write.assert_not_called()

    def test_sidecar_rejects_other_properties(self):
        """Only one canonical CORS variable may be imported by Compose."""
        self.assertIsNone(RELEASE.CORS.setting(""))
        approved = RELEASE.CORS.setting("https://one.example")
        self.assertEqual(RELEASE.CORS.value_of(approved), "https://one.example")
        for raw in (b"", approved + b"SPRING_FLYWAY_ENABLED=true\n", approved.rstrip(), approved.replace(b"\n", b"\r\n")):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                RELEASE.CORS.value_of(raw)

    def test_deployment_contract_remains_pinned(self):
        """Check the reviewed Compose, unchanged validator and unit hashes."""
        contract = json.loads((ROOT / "deploy/ec2-deployment-contract.json").read_text())
        for source, target in (
            ("compose.ec2.yml", "/opt/hobbytree/compose.ec2.yml"),
            ("scripts/validate-ec2-config.py", "/opt/hobbytree/scripts/validate-ec2-config.py"),
            ("deploy/hobbytree-backend.service", "/etc/systemd/system/hobbytree-backend.service"),
        ):
            self.assertEqual(RELEASE.CORE.sha((ROOT / source).read_bytes().replace(b"\r\n", b"\n")),
                             contract["files"][target])

    def test_launcher_pins_match_exact_installation_bytes(self):
        """Verify exact checkout bytes, which Git keeps LF on every platform."""
        for source, expected in RELEASE.PINNED_SHA256.items():
            with self.subTest(source=source):
                installed = (ROOT / source).read_bytes()
                self.assertEqual(RELEASE.CORE.sha(installed), expected)

    def test_db_target_requires_exact_host_database_and_verify_full_ca(self):
        """Reject a different endpoint, database, SSL policy or duplicate option."""
        target = {"host": "test.rds.amazonaws.com", "port": 5432, "dbname": "hobbytree"}
        jdbc = ("jdbc:postgresql://test.rds.amazonaws.com:5432/hobbytree?"
                "sslmode=verify-full&sslrootcert=/etc/hobbytree/certs/rds-ca.pem")
        validate = RELEASE.DATABASE.require_backend_target
        require = RELEASE.CORE.require
        validate(jdbc, target, require)
        for bad in (jdbc.replace("test.rds", "different.rds"), jdbc.replace("/hobbytree?", "/other?"),
                    jdbc.replace("verify-full", "require"), jdbc + "&sslmode=verify-full",
                    jdbc.replace("rds-ca.pem", "wrong.pem"), jdbc.replace(":5432", ":15432")):
            with self.subTest(bad=bad), self.assertRaises(RELEASE.CORE.ReleaseError):
                validate(bad, target, require)

    def test_legacy_acknowledgement_cannot_enter_release_lock(self):
        """The previous generic acknowledgement does not authorize the fixed security pair."""
        with patch.object(sys, "argv", ["launcher", "--request-id", "12-1", "--approval", RELEASE.CORE.APPROVAL]), \
                patch.object(RELEASE.CORE, "release_lock") as lock:
            self.assertEqual(RELEASE.main(), 1)
        lock.assert_not_called()


if __name__ == "__main__":
    unittest.main()
