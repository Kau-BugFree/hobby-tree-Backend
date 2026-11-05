"""Offline regressions for the release boundary, independent of AWS and Docker."""

import importlib.util
import json
import os
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location(
    "release_manifest", Path(__file__).resolve().parents[1] / "ci/release-manifest.py"
)
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)
ERROR = RELEASE.PUBLISH.VerificationError


class ReleaseManifestTests(unittest.TestCase):
    """Exercise append-only history, image identity and guarded release phases."""

    def setUp(self):
        """Use synthetic SQL and identifiers without changing repository migrations."""
        self.sql = [(f"V{i}__fixture.sql", f"SELECT {i};".encode()) for i in range(1, 10)]
        self.base = RELEASE.migrations(self.sql[:6])
        self.digest = "sha256:" + "a" * 64
        self.previous = "sha256:" + "b" * 64

    def test_new_versions_discovered_in_numeric_order(self):
        """New V7–V9 need no release-code change, and numeric sorting survives V10."""
        files = [("V10__fixture.sql", b"SELECT 10;"), *reversed(self.sql)]
        candidate = RELEASE.migrations(files)
        RELEASE.require_prefix(self.base, candidate)
        self.assertEqual(["7", "8", "9", "10"], [entry["version"] for entry in candidate[6:]])

    def test_code_only_allows_distinct_image_with_same_sql(self):
        """An unchanged SQL set can deploy a different manifest digest."""
        RELEASE.require_prefix(self.base, list(self.base))
        RELEASE.deployment_allowed({"schema_changed": False}, self.digest, self.previous)

    def test_reject_changed_removed_and_renamed_applied_sql(self):
        """The full applied prefix includes filenames, versions and exact bytes."""
        for files in (self.sql[:5], [("V1__fixture.sql", b"SELECT 100;"), *self.sql[1:]],
                      [("V1__renamed.sql", self.sql[0][1]), *self.sql[1:]]):
            with self.subTest(files=files), self.assertRaises(ERROR):
                RELEASE.require_prefix(self.base, RELEASE.migrations(files))

    def test_reject_duplicate_gapped_and_noncanonical_versions(self):
        """Reject reused, missing or ambiguous version identifiers before touching PostgreSQL."""
        for files in ([*self.sql, ("V9__other.sql", b"")], self.sql[:6] + self.sql[7:],
                      [("V01__ambiguous.sql", b"")], [("V1.1__noncanonical.sql", b"")]):
            with self.subTest(files=files), self.assertRaises(ERROR):
                RELEASE.migrations(files)

    def test_history_order_and_checksum_are_required(self):
        """Reject Flyway history with out-of-order rows or invalid CRC32 values."""
        rows = [f"{row['version']}\t{row['script']}\t{row['flyway_checksum']}" for row in self.base]
        self.assertEqual(6, len(RELEASE.history(Mock(read_text=lambda: "\n".join(rows)), self.base)))
        with self.assertRaises(ERROR):
            RELEASE.history(Mock(read_text=lambda: "\n".join(reversed(rows))), self.base)
        with self.assertRaises(ERROR):
            RELEASE.history(Mock(read_text=lambda: "\n".join(rows[:-1] + [rows[-1].rsplit("\t", 1)[0] + "\t0"])), self.base)

    def test_disabled_approval_fails_before_environment_or_credentials(self):
        """A missing enable flag cannot reach the Environment check."""
        with patch.dict(os.environ, {"RELEASE_AWS_ENABLED": "false"}), patch.object(
            RELEASE.PUBLISH, "check_environment"
        ) as environment, self.assertRaises(ERROR):
            RELEASE.approval_settings("publish")
        environment.assert_not_called()

    def test_reject_changed_schema_or_duplicate_digest(self):
        """A possibly incompatible schema or already running image must not reach SSM."""
        with self.assertRaises(ERROR):
            RELEASE.deployment_allowed({"schema_changed": True}, self.digest, self.previous)
        with self.assertRaises(ERROR):
            RELEASE.deployment_allowed({"schema_changed": False}, self.digest, self.digest)

    def test_reject_wrong_image_config_or_revision_before_reading_jar(self):
        """Check Docker's inspected metadata before creating any inspection container."""
        revision = "a" * 40
        image = {"Id": self.digest, "Os": "linux", "Architecture": "amd64",
                 "Config": {"User": "10001:10001", "Labels": {"org.opencontainers.image.revision": revision}}}
        for candidate, expected_id in ((image, self.previous), ({**image, "Config": {
                "User": "10001:10001", "Labels": {"org.opencontainers.image.revision": "b" * 40}}}, self.digest)):
            with patch.object(RELEASE.PUBLISH, "command", return_value=json.dumps([candidate]).encode()) as command:
                with self.assertRaises(ERROR):
                    RELEASE.inspect(expected_id, revision, self.base, expected_id)
                command.assert_called_once()

    def test_manifest_must_match_run_and_git_migrations(self):
        """A receipt from another run or SQL history cannot stand in for this release."""
        identity = {"run_id": "123", "attempt": "1", "schema_changed": False}
        manifest = {**identity, "verified": ["gradle", "spotless", "flyway", "jpa", "health"],
                    "migrations": [dict(row) for row in self.base]}
        RELEASE.verify_manifest(manifest, identity, self.base)
        with self.assertRaises(ERROR):
            RELEASE.verify_manifest({**manifest, "attempt": "2"}, identity, self.base)
        manifest["migrations"][0]["sha256"] = "0" * 64
        with self.assertRaises(ERROR):
            RELEASE.verify_manifest(manifest, identity, self.base)
