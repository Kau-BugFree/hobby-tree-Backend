"""Reuse the reviewed opt-in Haiku secret loader; launch a strict GPU factory."""
import os


def main():
    from approved_launcher import prepare_environment
    from gpu_runtime import ProfileError, require
    prepare_environment(os.environ)
    require(os.environ.get("COACH_DEVICE") == "cuda", "GPU_SETTINGS")
    require(os.environ.get("COACH_EMBEDDING_ENABLED") == "true"
            and os.environ.get("COACH_RERANKER_ENABLED") == "true", "GPU_SETTINGS")
    os.execvp("python", ["python", "-m", "uvicorn", "gpu_runtime:create_app", "--factory",
                        "--host", "0.0.0.0", "--port", "8003", "--workers", "1", "--no-access-log"])


if __name__ == "__main__":
    main()
