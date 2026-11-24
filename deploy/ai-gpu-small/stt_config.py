"""Minimal interface for the pinned STT module, independent of coach Settings."""
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    google_credentials_path: Path = Path("/run/secrets/google_credentials")
    stt_location: str = "global"
    stt_model: str = "long"
    stt_timeout_seconds: int = 30
