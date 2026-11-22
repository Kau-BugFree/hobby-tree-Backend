"""Export only immutable upstream source bytes; never copy a local .env or models."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

REVISION = "9364bface2638fb62c54edef6782ea58d5d73df9"
PREFIX = "src/activity-coach/backend/"


def export(repository, destination):
    destination = Path(destination)
    if destination.exists():
        raise ValueError("Source destination must be a new path")
    def git(*args):
        return subprocess.check_output(["git", "-C", str(repository), *args], stderr=subprocess.DEVNULL)
    names = git("ls-tree", "-r", "--name-only", REVISION, PREFIX).decode().splitlines()
    selected = []
    for name in names:
        relative = name[len(PREFIX):]
        if (relative == "requirements.txt" or relative.startswith("app/") and relative.endswith(".py")
                or relative.startswith("data/") and "/" not in relative[5:]
                and relative.endswith((".json", ".jsonl"))):
            selected.append((name, relative))
    if not selected or "requirements.txt" not in [relative for _, relative in selected]:
        raise ValueError("Pinned source is unavailable")
    destination.mkdir(parents=True)
    manifest = {"revision": REVISION, "files": {}}
    for name, relative in selected:
        raw = git("show", f"{REVISION}:{name}")
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
        manifest["files"][relative] = hashlib.sha256(raw).hexdigest()
    (destination / "source.json").write_bytes(json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode())
    return {"revision": REVISION, "files": len(selected)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("repository", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    print(json.dumps(export(args.repository, args.destination)))
