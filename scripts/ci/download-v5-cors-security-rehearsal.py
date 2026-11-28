"""Fetch hash-pinned historical ZIPs for offline CI only; never publish to ECR."""

import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("v5", ROOT / "scripts/ci/v5-artifact.py")
V5 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(V5)
TAG = "ci-archive-v5-rehearsal-20261128"
ARCHIVES = {
    "old": ("v5-original-13384572345.zip", 175025499,
            "309661aff37834b30c825381aab616af87d861d42a9e01926d9d8f08ac525444"),
    "security": ("v5-security-13442090551.zip", 179620427,
                 "0112efe29bd59a44b40563779607b4988f1b8b0f17aed69f18b38465f7d4e4e9"),
}


def require_context():
    V5.require(os.environ.get("GITHUB_ACTIONS") == "true" and
               os.environ.get("HOBBYTREE_CI") == "1" and
               os.environ.get("GITHUB_EVENT_NAME") == "pull_request" and
               os.environ.get("GITHUB_REPOSITORY") == "Kau-BugFree/hobby-tree-Backend",
               "Historical mirror is restricted to isolated PR rehearsal.")


def check_url(url):
    parsed = urlsplit(url)
    V5.require(parsed.scheme == "https" and parsed.hostname in
               ("github.com", "release-assets.githubusercontent.com") and
               not parsed.username and not parsed.password and parsed.port in (None, 443),
               "Untrusted archive mirror host.")


class MirrorRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        check_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def copy_verified(source, output, size, digest):
    total = 0
    actual = hashlib.sha256()
    while block := source.read(1024 * 1024):
        total += len(block)
        V5.require(total <= size, "Historical ZIP exceeds pinned size.")
        actual.update(block)
        output.write(block)
    V5.require(total == size and actual.hexdigest() == digest,
               "Historical ZIP size or SHA256 mismatch.")


def verify_old_source(expected):
    run = V5.api_json(f"actions/runs/{expected['run_id']}/attempts/1", expected)
    jobs = V5.api_json(f"actions/runs/{expected['run_id']}/attempts/1/jobs?per_page=100", expected)
    fields = dict(id=expected["run_id"], run_attempt=1, head_sha=expected["workflow_sha"],
                  workflow_id=expected["workflow_id"], path=expected["workflow_path"],
                  event="workflow_dispatch", status="completed", conclusion="success")
    V5.require(all(run.get(k) == v for k, v in fields.items()) and
               run.get("repository", {}).get("id") == expected["repository_id"] and
               run.get("head_repository", {}).get("id") == expected["repository_id"],
               "Historical source run mismatch.")
    V5.require(any(j.get("id") == expected["job_id"] and j.get("status") == "completed" and
                   j.get("conclusion") == "success" for j in jobs.get("jobs", [])),
               "Historical source job did not succeed.")


def download(kind, directory):
    require_context()
    name, size, digest = ARCHIVES[kind]
    if kind == "old":
        verify_old_source(V5.lock())
    V5.require(not directory.exists(), "Archive destination must be new.")
    directory.mkdir(parents=True, mode=0o700)
    url = f"https://github.com/Kau-BugFree/hobby-tree-Backend/releases/download/{TAG}/{name}"
    check_url(url)
    # Public mirror: never send GH_TOKEN or other credentials to asset hosts.
    with build_opener(MirrorRedirect()).open(Request(url), timeout=120) as source, \
            (directory / "artifact.zip").open("xb") as output:
        copy_verified(source, output, size, digest)
    if kind == "old":
        V5.extract(directory, V5.lock())
        V5.verify_files(directory, V5.lock())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=ARCHIVES)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    download(args.kind, args.directory)
