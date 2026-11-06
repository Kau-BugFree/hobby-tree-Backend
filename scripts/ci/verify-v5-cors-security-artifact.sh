#!/usr/bin/env bash
set -euo pipefail
umask 077
mkdir "$RUNNER_TEMP/v5-cors-security-original"
cd "$RUNNER_TEMP/v5-cors-security-original"
repo=Kau-BugFree/hobby-tree-Backend
run=42080492279
artifact=13442090551
revision=598b261624a35207cc774214ef11257187dd96e8
ci_commit=acdbd1732e9cedf77625af0e97cef9bf99882894
zip_digest=0112efe29bd59a44b40563779607b4988f1b8b0f17aed69f18b38465f7d4e4e9
image_id=sha256:7690824663e0cc45f5c48031b15b46ece14edf21aed885a1a4399ec7b0046767
schema=84bbd4049e3e926c5b725d3c3213b2d6980202973aaba31299f1a4999dee0026

gh api "repos/$repo/actions/runs/$run/attempts/1" |
  jq -e --arg commit "$ci_commit" '
    .id == 42080492279 and .run_attempt == 1 and .status == "completed" and
    .conclusion == "success" and .event == "workflow_dispatch" and
    .workflow_id == 397807172 and .head_branch == "ci/73-v5-cors-os-security" and
    .head_sha == $commit and .path == ".github/workflows/docker-validation.yml" and
    .repository.id == 1405678704 and .head_repository.id == .repository.id' >/dev/null
gh api "repos/$repo/actions/artifacts/$artifact" |
  jq -e --arg commit "$ci_commit" --arg digest "sha256:$zip_digest" '
    .id == 13442090551 and .expired == false and .digest == $digest and .size_in_bytes == 179620427 and
    .name == "verified-v5-cors-security-linux-amd64" and .workflow_run.id == 42080492279 and
    .workflow_run.head_branch == "ci/73-v5-cors-os-security" and .workflow_run.head_sha == $commit and
    .workflow_run.repository_id == 1405678704 and
    .workflow_run.head_repository_id == .workflow_run.repository_id' >/dev/null
gh api "repos/$repo/actions/runs/$run/attempts/1/jobs" |
  jq -e '[.jobs[] | select(.status == "completed" and .conclusion == "success") | .id] |
    contains([127431942046, 127431941557])' >/dev/null

gh api "repos/$repo/actions/artifacts/$artifact/zip" > artifact.zip
printf '%s  artifact.zip\n' "$zip_digest" | sha256sum -c -
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile
import re
import shutil
import stat
names = {"SHA256SUMS", "backend-linux-amd64.tar.gz", "receipt.json"}
with ZipFile("artifact.zip") as source:
    assert sorted(source.namelist()) == sorted(names)
    entries = source.infolist()
    assert sum(entry.file_size for entry in entries) < 250 * 1024 * 1024
    for entry in entries:
        assert stat.S_IFMT(entry.external_attr >> 16) in (0, stat.S_IFREG)
        with source.open(entry) as member, Path(entry.filename).open("xb") as target:
            shutil.copyfileobj(member, target)
entries = [re.fullmatch(r"([a-f0-9]{64})  (backend-linux-amd64\.tar\.gz|receipt\.json)", line)
           for line in Path("SHA256SUMS").read_text().splitlines()]
assert len(entries) == 2 and all(entries)
assert {entry[2] for entry in entries} == names - {"SHA256SUMS"}
PY
sha256sum --strict -c SHA256SUMS
printf '%s  backend-linux-amd64.tar.gz\n' 01f2a2d0a3bde2620231f97255a68d7d5718d8ff892b5c5c08f44a055c2c3235 | sha256sum --strict -c -
jq -e --arg revision "$revision" --arg image_id "$image_id" --arg schema "$schema" '
  .revision == $revision and .image_id == $image_id and .schema_fingerprint == $schema and
  .platform == "linux/amd64" and .run_id == "42080492279" and .attempt == "1" and
  .image == "hobbytree/backend:v5-security-ci"' receipt.json >/dev/null
docker load --platform linux/amd64 --input backend-linux-amd64.tar.gz
docker image inspect hobbytree/backend:v5-security-ci | jq -e --arg id "$image_id" --arg revision "$revision" '
  length == 1 and .[0].Id == $id and .[0].Os == "linux" and .[0].Architecture == "amd64" and
  .[0].Config.User == "10001:10001" and
  .[0].Config.Labels["org.opencontainers.image.revision"] == $revision' >/dev/null
container=$(docker create --network none --entrypoint /bin/true --platform linux/amd64 "$image_id")
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
