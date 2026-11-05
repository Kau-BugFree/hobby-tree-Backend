#!/usr/bin/env bash
set -euo pipefail
umask 077
mkdir "$RUNNER_TEMP/v5-cors-original"
cd "$RUNNER_TEMP/v5-cors-original"
repo=Kau-BugFree/hobby-tree-Backend
run=42062701908
artifact=13432224738
revision=50f2fcd198241e8d7b6ab594057652bfc22a0d37
ci_commit=500b3d3f6dda453cb4618651a70c111d4faf8606
zip_digest=4ba099aa6e408facc338f74a6f61d95a580cb818427b9cc97cdb77ccbd901573
image_id=sha256:5203315180507a1ca448e220ab07d5edaf252eccaadefe053435d38f122d35d8
schema=84bbd4049e3e926c5b725d3c3213b2d6980202973aaba31299f1a4999dee0026

gh api "repos/$repo/actions/runs/$run/attempts/1" |
  jq -e --arg commit "$ci_commit" '
    .id == 42062701908 and .run_attempt == 1 and .status == "completed" and
    .conclusion == "success" and .head_branch == "ci/v5-cors-preflight" and
    .head_sha == $commit and .path == ".github/workflows/docker-validation.yml" and
    .repository.id == 1405678704 and .head_repository.id == .repository.id' >/dev/null
gh api "repos/$repo/actions/artifacts/$artifact" |
  jq -e --arg commit "$ci_commit" --arg digest "sha256:$zip_digest" '
    .id == 13432224738 and .expired == false and .digest == $digest and
    .name == "verified-v5-cors-linux-amd64" and .workflow_run.id == 42062701908 and
    .workflow_run.head_branch == "ci/v5-cors-preflight" and .workflow_run.head_sha == $commit and
    .workflow_run.repository_id == 1405678704 and
    .workflow_run.head_repository_id == .workflow_run.repository_id' >/dev/null

gh api "repos/$repo/actions/artifacts/$artifact/zip" > artifact.zip
printf '%s  artifact.zip\n' "$zip_digest" | sha256sum -c -
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile
import re
import shutil
names = {"SHA256SUMS", "backend-linux-amd64.tar.gz", "receipt.json"}
with ZipFile("artifact.zip") as source:
    assert sorted(source.namelist()) == sorted(names)
    for name in names:
        with source.open(name) as member, Path(name).open("xb") as target:
            shutil.copyfileobj(member, target)
entries = [re.fullmatch(r"([a-f0-9]{64})  (backend-linux-amd64\.tar\.gz|receipt\.json)", line)
           for line in Path("SHA256SUMS").read_text().splitlines()]
assert len(entries) == 2 and all(entries)
assert {entry[2] for entry in entries} == names - {"SHA256SUMS"}
PY
sha256sum --strict -c SHA256SUMS
jq -e --arg revision "$revision" --arg image_id "$image_id" --arg schema "$schema" '
  .revision == $revision and .image_id == $image_id and .schema_fingerprint == $schema and
  .platform == "linux/amd64" and .run_id == "42062701908" and .attempt == "1" and
  .image == "hobbytree/backend:ci"' receipt.json >/dev/null
docker load --platform linux/amd64 --input backend-linux-amd64.tar.gz
docker image inspect hobbytree/backend:ci | jq -e --arg id "$image_id" --arg revision "$revision" '
  length == 1 and .[0].Id == $id and .[0].Os == "linux" and .[0].Architecture == "amd64" and
  .[0].Config.Labels["org.opencontainers.image.revision"] == $revision' >/dev/null
container=$(docker create --platform linux/amd64 hobbytree/backend:ci)
trap 'docker rm "$container" >/dev/null' EXIT
docker cp "$container:/app/hobbytree.jar" verified.jar
python3 - "$schema" <<'PY'
import hashlib
import re
import sys
import zipfile
prefix = "BOOT-INF/classes/db/migration/"
digest = hashlib.sha256()
with zipfile.ZipFile("verified.jar") as jar:
    names = sorted(name for name in jar.namelist() if name.startswith(prefix) and not name.endswith("/"))
    assert len(names) == 5
    for version, name in enumerate(names, 1):
        filename = name.removeprefix(prefix)
        assert re.fullmatch(rf"V{version}__[A-Za-z0-9_]+\.sql", filename), filename
        content = jar.read(name).replace(b"\r\n", b"\n").replace(b"\r", b"\n")
        digest.update(filename.encode() + b"\0" + hashlib.sha256(content).digest())
    assert not any("sponsoredcontent" in name.lower() for name in jar.namelist())
assert digest.hexdigest() == sys.argv[1], digest.hexdigest()
PY
printf 'PASS: original archive, Image ID %s, OCI Revision %s, linux/amd64, V1-V5.\n' "$image_id" "$revision"
