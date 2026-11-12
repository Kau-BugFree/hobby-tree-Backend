"""Configure the coach without exposing credentials in Compose or command arguments."""

import os
import re
import stat
from pathlib import Path

MODEL = "claude-haiku-5-5"


def read_secret(path, label):
    """Bounded, non-symlink file read; errors never contain paths or secret values."""
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "rb") as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
                raise ValueError()
            value = stream.read(4097)
        if len(value) > 4096:
            raise ValueError()
        return value.decode("ascii").strip()
    except (OSError, ValueError):
        raise ValueError(label + " must be a readable owner-only regular file") from None


def prepare_environment(environ, secret_dir=Path("/run/secrets")):
    """Paid mode is opt-in; inherited keys cannot accidentally enable it."""
    enabled = environ.get("COACH_LLM_ENABLED", "false")
    if enabled not in ("true", "false"):
        raise ValueError("COACH_LLM_ENABLED must be true or false")
    token = read_secret(secret_dir / "coach_service_token", "Coach service token")
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", token):
        raise ValueError("Coach service token has an invalid format")
    key = ""
    if enabled == "true":
        key = read_secret(secret_dir / "anthropic_api_key", "Anthropic API key")
        if not re.fullmatch(r"sk-ant-[A-Za-z0-9_-]{24,1024}", key):
            raise ValueError("Anthropic API key has an invalid format")
    environ["COACH_BACKEND_TOKEN"] = token
    environ["ANTHROPIC_API_KEY"] = key
    # Do not inherit another model, provider URL, or unbounded retry/token settings.
    environ.pop("ANTHROPIC_BASE_URL", None)
    environ["CLAUDE_MODEL"] = MODEL
    environ["CLAUDE_EFFORT"] = "medium"
    environ["CLAUDE_MAX_TOKENS"] = "4096"
    environ["CLAUDE_TIMEOUT_SECONDS"] = "10"
    environ["CLAUDE_MAX_RETRIES"] = "0"


def main():
    try:
        prepare_environment(os.environ)
    except ValueError as error:
        raise SystemExit(str(error)) from None
    os.execvp("python", ["python", "-m", "uvicorn", "app.main:create_app", "--factory",
                         "--host", "0.0.0.0", "--port", "8003", "--workers", "1",
                         "--no-access-log"])


if __name__ == "__main__":
    main()
