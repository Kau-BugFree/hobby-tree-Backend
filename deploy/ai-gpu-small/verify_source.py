"""Verify a prepared AI build context; no network or runtime configuration reads."""
import hashlib
import json
from pathlib import Path
import sys

REVISION = "9364bface2638fb62c54edef6782ea58d5d73df9"
MANIFEST_SHA256 = "13e3be96bf64283b8ca76c6669abbc2bd0f8e1c38779687755a20e2209c5bf67"


def verify(root):
    root = Path(root)
    raw = (root / "source.json").read_bytes()
    if hashlib.sha256(raw).hexdigest() != MANIFEST_SHA256:
        raise ValueError("AI_SOURCE_MANIFEST")
    manifest = json.loads(raw)
    if manifest["revision"] != REVISION:
        raise ValueError("AI_SOURCE_REVISION")
    expected = manifest["files"]
    actual = {path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file()}
    if actual != set(expected) | {"source.json"}:
        raise ValueError("AI_SOURCE_FILES")
    for relative, digest in expected.items():
        path = root / relative
        if (not relative or "\\" in relative or any(p in ("", ".", "..") for p in relative.split("/"))
                or Path(relative).is_absolute() or path.is_symlink()
                or not path.resolve().is_relative_to(root.resolve())):
            raise ValueError("AI_SOURCE_PATH")
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError("AI_SOURCE_HASH")
    return len(expected)


if __name__ == "__main__":
    print(json.dumps({"source_verified": True, "files": verify(sys.argv[1])}))
