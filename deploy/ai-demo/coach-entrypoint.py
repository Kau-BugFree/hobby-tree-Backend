"""Read the service token from a mounted file without putting it in Compose output."""

import os
from pathlib import Path

token = Path("/run/secrets/coach_service_token").read_text("utf-8").strip()
if len(token) < 32:
    raise SystemExit("Coach service token must contain at least 32 characters")
os.environ["COACH_BACKEND_TOKEN"] = token
# This compose is exclusively for offline local validation. Paid model access is
# a separate deployment decision, not inherited from the developer's environment.
os.environ["ANTHROPIC_API_KEY"] = ""
os.execvp("python", ["python", "-m", "uvicorn", "app.main:create_app", "--factory",
                     "--host", "0.0.0.0", "--port", "8003", "--workers", "1",
                     "--no-access-log"])
