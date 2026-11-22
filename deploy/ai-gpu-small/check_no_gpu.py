"""Exercise the image's real CUDA dependencies without pretending a GPU exists."""
import hashlib
import json
import os
from pathlib import Path

import torch
from gpu_runtime import AI_REVISION, ProfileError, cuda_info


def main():
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
