"""Exercise the real pinned constructors/fingerprint without loading model weights."""
from pathlib import Path
import tempfile
from types import SimpleNamespace as NS

from app.encoder import SentenceEncoder
from app.reranker import CrossEncoderReranker
from app.recipe_dense import model_fingerprint
from gpu_runtime import Gate, GuardedModel


def main():
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "BGE-m3-ko"
        path.mkdir()
        (path / "synthetic-model.txt").write_text("not model weights")
        encoder = SentenceEncoder(path, device="cuda", batch_size=1)
        reranker = CrossEncoderReranker(path, device="cuda", batch_size=1)
        assert encoder.max_length == reranker.max_length == 1024
        assert encoder.batch_size == reranker.batch_size == 1
        # The wrapper must forward every field inspected by recipe_dense.
        encoder._model = NS(dtype="torch.float16")
        wrapped = GuardedModel(encoder, Gate(), "embedding")
        assert model_fingerprint(encoder) == model_fingerprint(wrapped)
        assert wrapped.model_path == path and wrapped._model is encoder._model
    print("Pinned upstream batch/max_length/fingerprint contract passed; no model loaded")


if __name__ == "__main__":
    main()
