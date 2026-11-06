"""Publish the verified V5 security candidate using the original ECR protections."""

import importlib.util
import json
import os
from pathlib import Path


SPEC = importlib.util.spec_from_file_location(
    "cors_publication", Path(__file__).with_name("publish-v5-cors-artifact.py")
)
CORS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CORS)
V5 = CORS.V5
REVISION = "598b261624a35207cc774214ef11257187dd96e8"
ZIP_SHA256 = "0112efe29bd59a44b40563779607b4988f1b8b0f17aed69f18b38465f7d4e4e9"
ARCHIVE_SHA256 = "01f2a2d0a3bde2620231f97255a68d7d5718d8ff892b5c5c08f44a055c2c3235"


def main():
    """Fail closed before AWS access unless this exact manual publication is approved."""
    expected = V5.lock()
    required = {
        "GITHUB_EVENT_NAME": "workflow_dispatch",
        "GITHUB_REPOSITORY": expected["repository"],
        "GITHUB_REF": "refs/heads/develop",
        "V5_CORS_SECURITY_PUBLISH_ENABLED": "true",
        "CONFIRM_CANDIDATE_SHA": REVISION,
    }
    V5.require(all(os.environ.get(key) == value for key, value in required.items()),
               "Unapproved security candidate publication context.")
    directory = Path(os.environ["RUNNER_TEMP"]) / "v5-cors-security-original"
    V5.require(V5.sha(directory / "artifact.zip") == ZIP_SHA256, "Artifact ZIP SHA256 mismatch.")
    V5.require(V5.sha(directory / "backend-linux-amd64.tar.gz") == ARCHIVE_SHA256,
               "Image archive SHA256 mismatch.")
    expected.update(
        source_sha=REVISION, workflow_sha="acdbd1732e9cedf77625af0e97cef9bf99882894",
        run_id=42080492279, run_attempt=1, artifact_id=13442090551, zip_sha256=ZIP_SHA256,
        archive_sha256=ARCHIVE_SHA256,
        image_id="sha256:7690824663e0cc45f5c48031b15b46ece14edf21aed885a1a4399ec7b0046767",
    )
    V5.verify_inspect(json.loads(V5.command(["docker", "image", "inspect", expected["image_id"]])), expected)
    CORS.check_push_impact(expected)
    V5.publish(expected)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("FAIL: " + (str(error) if isinstance(error, V5.VerificationError) else type(error).__name__))
        raise SystemExit(1) from None
