"""Run the image's recipe generation loader with the trial's 256 MiB /tmp."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory

import numpy as np
from app.recipe_dense import load_generation


COUNT = 544_888
RELEASED_CORPUS = "ec0006b651507f0857953abb091b0f80cff18492e893c50f64af97680b40a07c"


def fixture(index):
    generation = index / "recipe-generations/fixture"
    generation.mkdir(parents=True)
    # The recipe bodies alone exceed /tmp. IDs sort differently from rowids.
    payload = "가" * 192
    expected = hashlib.sha256(b"recipe-corpus-v1")
    ids = []

    def records():
        yield 1, "activity", "activities", "unused", ""
        for number in range(1, COUNT + 1):
            rowid = number * 2
            recipe = f"r{COUNT - number}"
            ids.append(recipe)
            expected.update(f'[{rowid},"{recipe}","{payload}"]\n'.encode())
            yield rowid, recipe, "recipes", str(number % 2), payload

    with sqlite3.connect(index / "retrieval.sqlite") as db:
        db.execute("CREATE TABLE records(rowid INTEGER PRIMARY KEY, id TEXT, scope TEXT, hobby TEXT, payload TEXT)")
        db.execute("CREATE INDEX records_scope ON records(scope,hobby)")
        db.executemany("INSERT INTO records VALUES (?,?,?,?,?)", records())
    ids_path = generation / "ids.json"
    ids_path.write_text(json.dumps(ids), encoding="utf-8")
    vectors = generation / "vectors.npy"
    np.save(vectors, np.zeros((COUNT, 1), dtype=np.float32))
    return {
        "model_fingerprint": "fixture", "corpus_fingerprint": expected.hexdigest(),
        "dense_count": COUNT, "dimensions": 1,
        "ids_file": ids_path.relative_to(index).as_posix(),
        "vectors_file": vectors.relative_to(index).as_posix(),
        "ids_sha256": hashlib.sha256(ids_path.read_bytes()).hexdigest(),
        "vectors_sha256": hashlib.sha256(vectors.read_bytes()).hexdigest(),
    }


def check(index, manifest):
    # Model validation is covered separately; exercise corpus, IDs and vector checks here.
    vectors, ids = load_generation(index, manifest, None, model_hash=manifest["model_fingerprint"])
    assert len(ids) == vectors.shape[0] == COUNT
    print(json.dumps({"rows": len(ids), "corpus_sha256": manifest["corpus_fingerprint"],
                      "tmp_mib": 256, "generation_loaded": True}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--fixture-parent", type=Path)
    inputs.add_argument("--released-index", type=Path)
    args = parser.parse_args()
    space = os.statvfs("/tmp")
    assert space.f_blocks * space.f_frsize == 256 * 1024**2, "Run with --tmpfs /tmp:size=256m"
    if args.released_index:
        manifest = json.loads((args.released_index / "recipes.dense.json").read_bytes())
        assert manifest["corpus_fingerprint"] == RELEASED_CORPUS and manifest["dense_count"] == COUNT
        check(args.released_index, manifest)
    else:
        with TemporaryDirectory(dir=args.fixture_parent) as directory:
            index = Path(directory)
            check(index, fixture(index))
