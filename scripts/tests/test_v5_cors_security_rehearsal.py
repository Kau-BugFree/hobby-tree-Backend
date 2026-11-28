"""Historical mirrors cannot weaken publication gates or accept substituted bytes."""
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("mirror", ROOT / "scripts/ci/download-v5-cors-security-rehearsal.py")
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)


class RehearsalArchiveTest(unittest.TestCase):
    def test_exact_bytes_required(self):
        digest = hashlib.sha256(b"original").hexdigest()
        M.copy_verified(io.BytesIO(b"original"), io.BytesIO(), 8, digest)
        for data in (b"modified", b"short", b"original-extra"):
            with self.subTest(data=data), self.assertRaises(M.V5.VerificationError):
                M.copy_verified(io.BytesIO(data), io.BytesIO(), 8, digest)

    def test_publication_and_local_context_rejected_before_network(self):
        good = dict(GITHUB_ACTIONS="true", HOBBYTREE_CI="1", GITHUB_EVENT_NAME="pull_request",
                    GITHUB_REPOSITORY="Kau-BugFree/hobby-tree-Backend")
        with patch.dict(os.environ, good, clear=True):
            M.require_context()
        for override in ({"GITHUB_EVENT_NAME": "workflow_dispatch"}, {"GITHUB_ACTIONS": "false"},
                         {"GITHUB_REPOSITORY": "attacker/fork"}, {"HOBBYTREE_CI": "0"}):
            with patch.dict(os.environ, good | override, clear=True), patch.object(M, "build_opener") as network:
                with self.assertRaises(M.V5.VerificationError):
                    M.download("security", Path("unused"))
                network.assert_not_called()

    def test_redirects_stay_on_public_github_https(self):
        M.check_url("https://release-assets.githubusercontent.com/file")
        for url in ("http://github.com/a", "https://github.com.evil/a", "https://evil/a",
                    "https://token@github.com/a", "https://github.com:444/a"):
            with self.subTest(url=url), self.assertRaises(M.V5.VerificationError):
                M.check_url(url)

    def test_failed_original_source_blocks_archive(self):
        with patch.object(M.V5, "api_json", side_effect=[{}, {"jobs": []}]), \
                self.assertRaises(M.V5.VerificationError):
            M.verify_old_source(M.V5.lock())

    def test_original_zip_lock_and_extraction_still_used(self):
        self.assertEqual(M.ARCHIVES["old"][2], M.V5.lock()["zip_sha256"])
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp)
            (p / "artifact.zip").write_bytes(b"untrusted")
            with self.assertRaises(M.V5.VerificationError):
                M.V5.extract(p, M.V5.lock())
            self.assertEqual([x.name for x in p.iterdir()], ["artifact.zip"])

    def test_mirror_is_not_enabled_by_publication_workflow(self):
        workflow = (ROOT / ".github/workflows/v5-cors-security-ecr-publish.yml").read_text()
        self.assertNotIn("--rehearsal-archive", workflow)
        self.assertNotIn("download-v5-cors-security-rehearsal", workflow)


if __name__ == "__main__":
    unittest.main()
