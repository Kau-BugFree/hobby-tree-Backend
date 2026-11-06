"""Offline checks for the new publication entry point."""

import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "ci/publish-v5-cors-security-artifact.py"
SPEC = importlib.util.spec_from_file_location("security_publication", SCRIPT)
PUBLISHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISHER)


class SecurityPublicationTest(unittest.TestCase):
    """Only the verified candidate can reach the shared registry publisher."""

    def test_publication_uses_fixed_provenance_and_original_impact_checks(self):
        """Verify both archives and the loaded image before the AWS impact gate and push."""
        v5 = PUBLISHER.V5
        calls = []
        with patch.dict(os.environ, self.environment(), clear=True), \
                patch.object(v5, "sha", side_effect=[PUBLISHER.ZIP_SHA256, PUBLISHER.ARCHIVE_SHA256]), \
                patch.object(v5, "command", return_value=b"[]"), \
                patch.object(v5, "verify_inspect", side_effect=lambda *_: calls.append("inspect")), \
                patch.object(PUBLISHER.CORS, "check_push_impact", side_effect=lambda _: calls.append("impact")), \
                patch.object(v5, "publish", side_effect=lambda _: calls.append("publish")) as publish:
            PUBLISHER.main()
        self.assertEqual(calls, ["inspect", "impact", "publish"])
        (expected,), _ = publish.call_args
        self.assertEqual(expected["source_sha"], PUBLISHER.REVISION)
        self.assertEqual((expected["run_id"], expected["artifact_id"]), (42080492279, 13442090551))

    def test_bad_context_or_archive_never_reaches_aws(self):
        """Reject an unapproved branch, disabled flag, wrong SHA or archive mismatch."""
        v5 = PUBLISHER.V5
        for overrides in (
            {"GITHUB_REF": "refs/heads/main"},
            {"GITHUB_EVENT_NAME": "pull_request"},
            {"V5_CORS_SECURITY_PUBLISH_ENABLED": ""},
            {"CONFIRM_CANDIDATE_SHA": "50f2fcd198241e8d7b6ab594057652bfc22a0d37"},
            {},
        ):
            with self.subTest(overrides=overrides), \
                    patch.dict(os.environ, self.environment() | overrides, clear=True), \
                    patch.object(v5, "sha", side_effect=[PUBLISHER.ZIP_SHA256, "incorrect"]), \
                    patch.object(PUBLISHER.CORS, "check_push_impact") as impact, \
                    patch.object(v5, "publish") as publish, self.assertRaises(v5.VerificationError):
                PUBLISHER.main()
            impact.assert_not_called()
            publish.assert_not_called()

    @staticmethod
    def environment():
        """Return the GitHub values needed for an approved test invocation."""
        return {
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_REPOSITORY": "Kau-BugFree/hobby-tree-Backend",
            "GITHUB_REF": "refs/heads/develop",
            "V5_CORS_SECURITY_PUBLISH_ENABLED": "true",
            "CONFIRM_CANDIDATE_SHA": PUBLISHER.REVISION,
            "RUNNER_TEMP": "fixture",
        }


if __name__ == "__main__":
    unittest.main()
