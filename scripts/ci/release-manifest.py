"""Bind a develop release, its verified Flyway history and the exact Docker archive."""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile
import zlib

SPEC = importlib.util.spec_from_file_location("publication", Path(__file__).with_name("v5-artifact.py"))
PUBLISH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISH)
require = PUBLISH.require
SQL = "src/main/resources/db/migration/"
JAR = "BOOT-INF/classes/db/migration/"
SHA = r"[0-9a-f]{40}"
DIGEST = r"sha256:[0-9a-f]{64}"
REPOSITORY = "Kau-BugFree/hobby-tree-Backend"
TARGET = {"repository": REPOSITORY, "oidc_sub_prefix": "repo:Kau-BugFree@336851079/hobby-tree-Backend@1405678704",
          "account_id": "494915081306", "region": "ap-northeast-2", "ecr_repository": "hobbytree/backend"}


def migrations(files):
    """Require the project's contiguous integer Flyway naming convention."""
    entries = {}
    for name, content in files:
        match = re.fullmatch(r"V([1-9][0-9]{0,8})__[A-Za-z0-9_]+\.sql", name)
        require(match is not None, "Unsupported migration filename.")
        version = int(match[1])
        require(version not in entries, "Duplicate migration version.")
        # Flyway reads UTF-8 lines without terminators, removes the leading BOM and stores a signed CRC32.
        canonical = content.decode("utf-8-sig").replace("\r", "").replace("\n", "").encode()
        checksum = zlib.crc32(canonical)
        entries[version] = {"version": str(version), "script": name, "sha256": hashlib.sha256(content).hexdigest(),
                            "flyway_checksum": checksum if checksum < 2**31 else checksum - 2**32}
    require(0 < len(entries) <= 1000 and sorted(entries) == list(range(1, len(entries) + 1)),
            "Migration versions must be consecutive from one.")
    return [entries[version] for version in sorted(entries)]


def require_prefix(baseline, candidate):
    """An applied migration cannot disappear, change name, move or change bytes."""
    require(bool(baseline) and candidate[:len(baseline)] == baseline, "Applied baseline SQL was removed or changed.")


def git(*args):
    """Read repository objects using argument arrays."""
    return PUBLISH.command(["git", *args])


def git_files(revision):
    """Read SQL bytes directly from the fixed Git tree, independent of checkout line endings."""
    names = git("ls-tree", "-r", "--name-only", "-z", revision, "--", SQL).decode().split("\0")
    return [(name.removeprefix(SQL), git("show", revision + ":" + name)) for name in names if name]


def context():
    """Bind the release to a manual develop dispatch and its actual checkout."""
    source, baseline = os.environ["GITHUB_SHA"], os.environ["BASELINE_SHA"]
    require(os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch" and
            os.environ.get("GITHUB_REPOSITORY") == REPOSITORY and os.environ.get("GITHUB_REF") == "refs/heads/develop",
            "Only manual develop releases are allowed.")
    require(re.fullmatch(SHA, source) and re.fullmatch(SHA, baseline) and os.environ["RELEASE_SHA"] == source
            and git("rev-parse", "HEAD").decode().strip() == source,
            "Release or baseline checkout mismatch.")
    require(subprocess.run(["git", "merge-base", "--is-ancestor", baseline, source], timeout=30).returncode == 0,
            "Baseline must be an ancestor of the release SHA.")
    return source, baseline


def history(path, candidate):
    """Read signed Flyway CRC32 values from the real isolated history in execution order."""
    rows = [line.split("\t") for line in path.read_text().splitlines()]
    require(len(rows) == len(candidate), "Incomplete Flyway history.")
    for row, entry in zip(rows, candidate):
        require(len(row) == 3 and row[:2] == [entry["version"], entry["script"]], "Unexpected Flyway history order.")
        require(row[2] == str(entry["flyway_checksum"]), "Flyway checksum does not match the Git SQL.")
    return candidate


def inspect(image_ref, revision, expected_migrations, image_id=None):
    """Check the loaded image and inspect packaged SQL without starting its entrypoint."""
    images = json.loads(PUBLISH.command(["docker", "image", "inspect", image_ref]))
    require(len(images) == 1 and re.fullmatch(DIGEST, images[0]["Id"]), "Invalid Docker image identity.")
    image_id = image_id or images[0]["Id"]
    PUBLISH.verify_inspect(images, {"image_id": image_id, "platform": "linux/amd64", "source_sha": revision})
    container = PUBLISH.command(["docker", "create", "--network", "none", "--entrypoint", "/bin/true", image_id]).decode().strip()
    require(re.fullmatch(r"[0-9a-f]{64}", container), "Invalid inspection container.")
    try:
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary, "backend.jar")
            PUBLISH.command(["docker", "cp", container + ":/app/hobbytree.jar", str(jar)])
            require(jar.stat().st_size <= 256 * 1024 * 1024, "Unexpected JAR size.")
            with zipfile.ZipFile(jar) as archive:
                entries = [item for item in archive.infolist() if item.filename.startswith(JAR) and not item.is_dir()]
                require(len(entries) <= 1000 and sum(item.file_size for item in entries) <= 2 * 1024 * 1024,
                        "Unexpected packaged SQL size.")
                packaged = migrations((item.filename.removeprefix(JAR), archive.read(item)) for item in entries)
            require(packaged == expected_migrations, "Packaged SQL does not match the fixed Git tree.")
            return image_id, PUBLISH.SCHEMA.schema_fingerprint(jar)
    finally:
        PUBLISH.command(["docker", "rm", container])


def output(**values):
    """Expose only validated identifiers and decision flags to dependent jobs."""
    with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
        for key, value in values.items():
            stream.write(f"{key}={value}\n")


def deployment_allowed(manifest, digest, current):
    """Leave changed schemas and duplicate deployment requests outside the generic path."""
    require(manifest["schema_changed"] is False, "A changed SQL set requires a separately reviewed DB transition.")
    require(re.fullmatch(DIGEST, digest) and re.fullmatch(DIGEST, current) and digest != current,
            "Deployment requires distinct immutable current and candidate digests.")


def approval_settings(action):
    """Use a separate protected GitHub Environment for publication and EC2 replacement."""
    expected = {**TARGET, "environment": "hobbytree-release-" + action}
    require(os.environ.get("RELEASE_AWS_ENABLED") == "true", "AWS release jobs are disabled.")
    PUBLISH.check_environment(expected)
    return expected


def verify_manifest(manifest, identity, candidate):
    """Check that the hashed manifest describes this run and the full candidate SQL history."""
    require(all(manifest.get(key) == value for key, value in identity.items()) and
            manifest.get("verified") == ["gradle", "spotless", "flyway", "jpa", "health"], "Manifest identity mismatch.")
    require(manifest.get("migrations") == candidate, "Manifest migration mismatch.")


def main():
    """Run preflight, seal, verification, or one explicitly approved release phase."""
    action = sys.argv[1]
    if action in ("check-ci-source", "check-ci-image"):
        source = os.environ["GITHUB_SHA"]
        require(re.fullmatch(SHA, source) and git("rev-parse", "HEAD").decode().strip() == source,
                "CI checkout mismatch.")
        candidate = migrations(git_files(source))
        if action == "check-ci-source":
            baseline = os.environ.get("PR_BASE_SHA") or source
            require(re.fullmatch(SHA, baseline), "Invalid CI base revision.")
            require_prefix(migrations(git_files(baseline)), candidate)
            return
        image_id, fingerprint = inspect(os.environ["BACKEND_IMAGE"], source, candidate)
        print(f"Verified CI image {image_id} at {source} with SQL set {fingerprint}.")
        return
    source, baseline = context()
    work = Path(os.environ["RUNNER_TEMP"], "hobbytree-release")
    directory = work / "artifact"
    archive = directory / "image.tar"
    manifest_path = directory / "release.json"
    before_files, after_files = git_files(baseline), git_files(source)
    before, after = migrations(before_files), migrations(after_files)
    require_prefix(before, after)
    changed = before != after
    run_id, attempt = os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"]
    require(re.fullmatch(r"[0-9]{1,20}", run_id) and re.fullmatch(r"[0-9]{1,5}", attempt), "Invalid release run identity.")
    identity = {"format": 1, "repository": REPOSITORY, "source_sha": source, "baseline_sha": baseline,
                "run_id": run_id, "attempt": attempt, "platform": "linux/amd64", "schema_changed": changed}
    if action == "prepare":
        if os.environ.get("REQUEST_DEPLOY") == "true":
            require(not changed and os.environ.get("REQUEST_PUBLISH") == "true",
                    "Same-run deploy requires publish and an unchanged SQL set.")
            require(not os.environ.get("V45_APPROVAL"), "V45 uses its separately signed path.")
            require(re.fullmatch(DIGEST, os.environ.get("EXPECTED_CURRENT_DIGEST", "")) and
                    all(os.environ.get(key) == "true" for key in ("ACK_DOWNTIME", "ACK_SCHEMA", "ACK_ROLLBACK")),
                    "Deployment acknowledgements or current digest missing.")
            approval_settings("deploy")
        if os.environ.get("REQUEST_PUBLISH") == "true":
            approval_settings("publish")
        (work / "baseline").mkdir(parents=True)
        for name, content in before_files:
            (work / "baseline" / name).write_bytes(content)
        require(migrations((p.name, p.read_bytes()) for p in Path(SQL).iterdir() if p.is_file()) == after,
                "Checkout migration bytes differ from Git.")
        return
    if action == "seal":
        verified = history(work / "history.tsv", after)
        image_id, sql_set = inspect(os.environ["LOCAL_IMAGE"], source, after)
        directory.mkdir()
        PUBLISH.command(["docker", "image", "save", image_id, "--output", str(archive)], timeout=180)
        manifest = {**identity, "migrations": verified, "image_id": image_id, "sql_set_sha256": sql_set,
                    "archive_sha256": PUBLISH.sha(archive), "verified": ["gradle", "spotless", "flyway", "jpa", "health"]}
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
        output(manifest_sha=PUBLISH.sha(manifest_path), schema_changed=str(changed).lower(),
               artifact_name=f"backend-release-{source}-{run_id}-{attempt}")
        print(manifest_path.read_text())
        return
    if action == "deploy-check":
        require(os.environ.get("REQUEST_DEPLOY") == "true", "Deployment was not requested.")
        deployment_allowed(identity, os.environ["IMAGE_DIGEST"], os.environ["EXPECTED_CURRENT_DIGEST"])
        require(os.environ.get("IMAGE_REVISION") == source and not os.environ.get("V45_APPROVAL"),
                "Deployment receipt or legacy approval mismatch.")
        approval_settings("deploy")
        return
    require(action in ("verify-publish", "publish"), "Unknown release phase.")
    require(os.environ.get("REQUEST_PUBLISH") == "true", "Publication was not requested.")
    require(PUBLISH.sha(manifest_path) == os.environ["MANIFEST_SHA"], "Release manifest hash mismatch.")
    manifest = json.loads(manifest_path.read_text())
    verify_manifest(manifest, identity, after)
    require(PUBLISH.sha(archive) == manifest["archive_sha256"], "Archive hash mismatch.")
    if action == "verify-publish":
        approval_settings("publish")
        PUBLISH.command(["docker", "image", "load", "--input", str(archive)], timeout=180)
        _, sql_set = inspect(manifest["image_id"], source, after, manifest["image_id"])
        require(sql_set == manifest["sql_set_sha256"], "Image SQL fingerprint mismatch.")
        return
    # Only this fixed, independently approved job receives ECR credentials.
    require(re.fullmatch(r"[1-9][0-9]{0,19}", os.environ["RELEASE_ARTIFACT_ID"]), "Invalid release artifact ID.")
    published_fields = ("source_sha", "run_id", "platform", "image_id", "archive_sha256", "sql_set_sha256")
    expected = {**TARGET, **{key: manifest[key] for key in published_fields}, "run_attempt": attempt,
                "artifact_id": int(os.environ["RELEASE_ARTIFACT_ID"]), "workflow_sha": source,
                "release_manifest_sha256": os.environ["MANIFEST_SHA"], "baseline_sha": baseline,
                "schema_changed": changed}
    record = PUBLISH.publish(expected, evidence_name="release-publication")
    digest = record["registry_manifest_digest"]
    output(digest=digest, revision=source, image_id=manifest["image_id"])


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Release gate failed: " + (str(error) if isinstance(error, PUBLISH.VerificationError) else type(error).__name__), file=sys.stderr)
        raise SystemExit(1) from None
