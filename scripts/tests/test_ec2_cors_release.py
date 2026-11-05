"""Offline tests for the CORS setting and the existing release gate."""

import importlib.util
import json
from pathlib import Path
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
        release.runtime = Path("/run/hobbytree/secrets/CORS_ALLOWED_ORIGINS")
        release.before = b"https://previous.example"
        with patch.object(RELEASE.CORE.Deployment, "start_backend") as start, \
                patch.object(RELEASE.CORS, "persistent_value", return_value=release.before), \
                patch.object(RELEASE.CORS, "read", return_value=release.before), \
                patch.object(RELEASE.CORS, "write") as write:
            release.start_backend()
            self.assertEqual([call.args for call in write.call_args_list], [
                (RELEASE.CORS.PERSISTENT, release.desired, 0o600), (release.runtime, release.desired, 0o444)])
            start.assert_called_once()
        release.record["rollback_stage"] = "start-previous-backend"
        with patch.object(RELEASE.CORE.Deployment, "start_backend") as start, \
                patch.object(RELEASE.CORS, "persistent_value", return_value=release.desired), \
                patch.object(RELEASE.CORS, "read", return_value=release.desired), \
                patch.object(RELEASE.CORS, "write") as write:
            release.start_backend()
            self.assertEqual([call.args for call in write.call_args_list], [
                (RELEASE.CORS.PERSISTENT, release.before, 0o600), (release.runtime, release.before, 0o444)])
            start.assert_called_once()

    def test_runtime_drift_blocks_recovery_mutation(self):
        """Do not overwrite settings that belong to neither side of the release."""
        release = RELEASE.CorsRelease(None, ROOT, "1-1", "https://new.example")
        release.runtime = Path("/run/hobbytree/secrets/CORS_ALLOWED_ORIGINS")
        release.before = None
        release.record["rollback_stage"] = "start-previous-backend"
        with patch.object(RELEASE.CORE.Deployment, "start_backend") as start, \
                patch.object(RELEASE.CORS, "persistent_value", return_value=b"https://changed.example"), \
                patch.object(RELEASE.CORS, "read", return_value=None), \
                patch.object(RELEASE.CORS, "write") as write:
            with self.assertRaises(RELEASE.CORE.ReleaseError):
                release.start_backend()
            start.assert_not_called()
            write.assert_not_called()

    def test_legacy_contract_remains_pinned(self):
        """Retain the existing Compose, validator and unit hashes."""
        contract = json.loads((ROOT / "deploy/ec2-deployment-contract.json").read_text())
        for source, target in (
            ("compose.ec2.yml", "/opt/hobbytree/compose.ec2.yml"),
            ("scripts/validate-ec2-config.py", "/opt/hobbytree/scripts/validate-ec2-config.py"),
            ("deploy/hobbytree-backend.service", "/etc/systemd/system/hobbytree-backend.service"),
        ):
            self.assertEqual(RELEASE.CORE.sha((ROOT / source).read_bytes().replace(b"\r\n", b"\n")),
                             contract["files"][target])


if __name__ == "__main__":
    unittest.main()
