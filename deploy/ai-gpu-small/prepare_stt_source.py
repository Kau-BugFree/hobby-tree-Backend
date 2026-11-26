"""Export only the reviewed STT modules; never import the unmerged agent refactor."""
import argparse
import hashlib
from pathlib import Path
import subprocess

REVISION = "e81cfbf88a49e93aa8f702a9e8bf871f01edb6a9"
PREFIX = "src/activity-coach/backend/app/"
FILES = {
    "transcribe.py": "651ca0c05465f820e4ff9eb6cf1397746d3139ccaee0aae73bd3628d28e05316",
    "mp4_faststart.py": "422420ec9b7819360015ae30b236ebc8ee414cacfc5d5a4985efcd1cbfb78f55",
}


def verify(directory):
    directory = Path(directory)
    if directory.is_symlink() or {p.name for p in directory.iterdir()} != set(FILES):
        raise ValueError("STT export file set mismatch")
    for name, digest in FILES.items():
        path = directory / name
        if path.is_symlink() or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise ValueError("STT export integrity mismatch")


def export(repository, destination):
    payloads = {name: subprocess.check_output(
        ["git", "-C", str(repository), "show", REVISION + ":" + PREFIX + name]) for name in FILES}
    if any(hashlib.sha256(data).hexdigest() != FILES[name] for name, data in payloads.items()):
        raise ValueError("STT source integrity mismatch")
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    for name, data in payloads.items():
        (destination / name).write_bytes(data)
    verify(destination)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+")
    args = parser.parse_args()
    if len(args.paths) == 1:
        verify(args.paths[0])
    elif len(args.paths) == 2:
        export(*args.paths)
    else:
        parser.error("Provide an export to verify, or repository and new export directory")
