"""Adapt the verified AI source copy to the released Windows-built indexes."""
import hashlib
from pathlib import Path


def main(root):
    # The build has already verified every input against the pinned AI revision.
    # Change only the traversal order, retaining upstream's per-file and config hashes.
    module = root / "app/recipe_dense.py"
    source = module.read_text(encoding="utf-8")
    original = "files = sorted(path for path in root.rglob('*') if path.is_file())"
    if source.count(original) != 1 or source.count("from pathlib import Path\n") != 1:
        raise ValueError("Review the upstream model fingerprint algorithm")
    source = source.replace("from pathlib import Path\n", "from pathlib import Path, PureWindowsPath\n")
    source = source.replace(original,
        "files = sorted((path for path in root.rglob('*') if path.is_file()),\n"
        "                   key=lambda path: PureWindowsPath(path.relative_to(root)))")

    # Scanning the rowid table satisfies ORDER BY without sorting recipe payloads in /tmp.
    # Limit both replacements to the corpus digest and the generation loader's ID list.
    for statement in (
        '''for row in db.execute("SELECT rowid,id,payload FROM records WHERE scope='recipes' ORDER BY rowid"):''',
        '''ids = [row[0] for row in db.execute("SELECT id FROM records WHERE scope='recipes' ORDER BY rowid")]''',
    ):
        if source.count(statement) != 1:
            raise ValueError("Review the upstream recipe generation queries")
        source = source.replace(statement, statement.replace("FROM records WHERE", "FROM records NOT INDEXED WHERE"))
    module.write_text(source, encoding="utf-8")

    # The knowledge index versions the raw catalog bytes. Its producer used CRLF.
    knowledge = root / "data/coaching_knowledge.jsonl"
    data = knowledge.read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
    if hashlib.sha256(b"hobbytree-fts-v2.0" + data).hexdigest() != (
            "19a09c5517720a4baa8e0f33fb52bd2be4877457f7f7df10d0b39cc38e2483fe"):
        raise ValueError("Knowledge catalog differs from the released index")
    knowledge.write_bytes(data)


if __name__ == "__main__":
    main(Path("/service"))
