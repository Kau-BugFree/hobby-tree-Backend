"""Exercise the image's real CUDA dependencies without pretending a GPU exists."""
import hashlib
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace

import torch
from gpu_runtime import AI_REVISION, ProfileError, cuda_info
from app.recipe_dense import model_fingerprint
from app.retrieval import input_version


def check_released_indexes():
    knowledge = Path("/service/data/coaching_knowledge.jsonl")
    assert input_version([knowledge]) == "19a09c5517720a4baa8e0f33fb52bd2be4877457f7f7df10d0b39cc38e2483fe"
    with TemporaryDirectory() as tmp:
        root = Path(tmp)
        encoder = SimpleNamespace(model_path=root, name="fixture", max_length=1024,
                                  _model=SimpleNamespace(dtype="torch.float16"))
        config = {"format": 1, "name": "fixture", "max_length": 1024, "dtype": "torch.float16",
                  "normalize_embeddings": True, "versions": {"torch": "2.11.0+cu128", "numpy": "2.5.4",
                  "sentence-transformers": "6.2.0", "transformers": "5.20.2"}}
        expected = hashlib.sha256(json.dumps(config, sort_keys=True).encode())
        # Explicit producer order. POSIX ordering would place README.md first.
        for name, data in [("a.txt", b"weights"), ("README.md", b"metadata")]:
            (root / name).write_bytes(data)
            expected.update(name.encode())
            expected.update(hashlib.sha256(data).digest())
        assert model_fingerprint(encoder) == expected.hexdigest()
        encoder._model.dtype = "torch.float32"
        assert model_fingerprint(encoder) != expected.hexdigest()
        encoder._model.dtype = "torch.float16"
        (root / "a.txt").write_bytes(b"changed")
        assert model_fingerprint(encoder) != expected.hexdigest()


def main():
    check_released_indexes()
    assert torch.__version__ == "2.11.0+cu128"
    assert torch.version.cuda == "12.8" and not torch.cuda.is_available()
    assert os.geteuid() == 10001 and os.environ["COACH_LLM_ENABLED"] == "false"
    try:
        cuda_info(torch)
    except ProfileError as error:
        assert str(error) == "GPU_UNAVAILABLE"
    else:
        raise AssertionError("Missing GPU accepted")
    print(json.dumps({"torch": torch.__version__, "cuda_available": False,
        "expected_rejection": "GPU_UNAVAILABLE", "hardware_test_passed": False,
        "ai_revision": AI_REVISION,
        "runtime_sha256": hashlib.sha256(Path("/service/gpu_runtime.py").read_bytes()).hexdigest()}))


if __name__ == "__main__":
    main()
