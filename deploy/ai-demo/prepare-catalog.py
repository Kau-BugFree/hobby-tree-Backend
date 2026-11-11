"""Match copied catalog line endings to the supplied index; never alter model files.

The team's 2026-11-11 package uses LF for activities, CRLF for knowledge. Git's
Windows checkout conversion cannot preserve both. Only accept a byte fingerprint
match after changing line endings; content changes require rebuilding the index.
"""

import argparse
import hashlib
import json
from pathlib import Path


def matching_bytes(original, expected):
    lf = original.replace(b"\r\n", b"\n")
    for candidate in (original, lf, lf.replace(b"\n", b"\r\n")):
        actual = hashlib.sha256(b"hobbytree-fts-v2.0" + candidate).hexdigest()
        if actual == expected:
            return candidate
    raise ValueError("Catalog content differs from supplied index; rebuild the index")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, required=True, help="Disposable exported source only")
    parser.add_argument("--index", type=Path, required=True, help="Read-only original package index")
    args = parser.parse_args()
    prepared = []
    for scope, filename in (("activities", "activities.jsonl"),
                            ("knowledge", "coaching_knowledge.jsonl")):
        manifest = json.loads((args.index / f"{scope}.dense.json").read_text("utf-8"))
        if manifest["index_version"] != "hobbytree-fts-v2.0":
            raise ValueError("Unsupported index version")
        path = args.data / filename
        prepared.append((path, matching_bytes(path.read_bytes(), manifest["version"])))
    # Validate both before writing either; never substitute a different manifest hash.
    for path, content in prepared:
        path.write_bytes(content)
        print(path.name + ": supplied index fingerprint matched")


if __name__ == "__main__":
    main()
