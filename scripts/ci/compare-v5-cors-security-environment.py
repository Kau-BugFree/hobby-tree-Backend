"""Allow only absent/empty optional profile settings in the historical fixture."""
import importlib.util
import json
from pathlib import Path
import sys

SPEC = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parents[1] / "deploy/ec2-release.py")
CORE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CORE)

if __name__ == "__main__":
    CORE.require_matching_environment(json.loads(Path(sys.argv[1]).read_text()),
                                      json.loads(Path(sys.argv[2]).read_text()))
