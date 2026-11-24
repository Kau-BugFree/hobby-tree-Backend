"""Opt-in STT route for the existing GPU coach; no chat/session/model changes."""
import asyncio
import hmac
import json
import logging
import os
from pathlib import Path
import re
import stat

from fastapi import HTTPException, Request
from starlette.requests import ClientDisconnect

MAX_AUDIO_BYTES = 7 * 1024 * 1024
REQUEST_SECONDS = 40
GOOGLE_CREDENTIALS = Path("/run/secrets/google_credentials")
LOG = logging.getLogger(__name__)


def read_account(path=GOOGLE_CREDENTIALS):
    """Read a bounded, private regular file without following a symlink or FIFO."""
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as handle:
        info = os.fstat(handle.fileno())
        if (not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o400
                or info.st_uid != 10001 or info.st_gid != 10001 or not 0 < info.st_size <= 16384):
            raise ValueError("STT credential file contract")
        data = handle.read(16385)
    if len(data) > 16384:
        raise ValueError("STT credential file limit")
    account = json.loads(data)
    if (not isinstance(account, dict) or account.get("type") != "service_account"
            or not isinstance(account.get("project_id"), str)
            or not re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", account["project_id"])):
        raise ValueError("STT credential project contract")
    return account


def make_transcriber():
    # Lazy imports: disabled STT never reads a key or initializes Google auth.
    from google.auth import jwt
    from stt_vendor.config import Settings
    from stt_vendor import transcribe

    # The upstream 400 diagnostic includes provider prose. Keep only local status
    # diagnostics, never provider messages, transcripts, audio or account values.
    transcribe.logger.disabled = True
    try:
        account = read_account()
        client = transcribe.GoogleTranscriber(Settings())
        credentials = jwt.Credentials.from_service_account_info(
            account, audience="https://speech.googleapis.com/")
        client._account = (credentials, "https://speech.googleapis.com/v2/projects/"
                           + account["project_id"] + "/locations/global/recognizers/_:recognize")
        return client
    except Exception:
        raise ValueError("STT credential initialization failed") from None


async def convert_until_disconnect(request, client, body):
    """Uvicorn does not cancel handlers after upload when the peer disconnects."""
    async def disconnected():
        while True:
            if (await request.receive())["type"] == "http.disconnect":
                return

    provider = asyncio.create_task(client.transcribe(body))
    monitor = asyncio.create_task(disconnected())
    try:
        done, _ = await asyncio.wait((provider, monitor), return_when=asyncio.FIRST_COMPLETED)
        if monitor in done:
            monitor.result()
            raise ClientDisconnect()
        return provider.result()
    finally:
        for task in (provider, monitor):
            if not task.done():
                task.cancel()
        # Do not free the concurrency slot while an upstream task is still alive.
        await asyncio.gather(provider, monitor, return_exceptions=True)


def attach_stt(api, service_token, environment=None):
    environment = os.environ if environment is None else environment
    enabled = environment.get("COACH_STT_ENABLED", "false")
    if enabled not in ("true", "false"):
        raise ValueError("Literal COACH_STT_ENABLED required")
    if not service_token:
        raise ValueError("STT service token required")
    # Reject hidden ADC/model overrides rather than selecting a different account.
    forbidden = {"GOOGLE_APPLICATION_CREDENTIALS", "COACH_STT_MODEL", "COACH_STT_LOCATION",
                 "COACH_STT_TIMEOUT_SECONDS"}
    if forbidden.intersection(environment):
        raise ValueError("Use the fixed STT credential and recognition contract")
    client = make_transcriber() if enabled == "true" else None
    slots = asyncio.Queue(maxsize=2)
    for _ in range(2):
        slots.put_nowait(True)

    @api.post("/backend/coach/transcribe")
    async def transcribe_audio(request: Request):
        supplied = request.headers.get("x-coach-service-token", "")
        if not hmac.compare_digest(supplied.encode(), service_token.encode()):
            raise HTTPException(401, "STT_UNAUTHORIZED")
        if client is None:
            raise HTTPException(503, "STT_DISABLED")
        if not request.headers.get("content-type", "").lower().startswith("audio/"):
            raise HTTPException(415, "STT_AUDIO_REQUIRED")
        try:
            slots.get_nowait()
        except asyncio.QueueEmpty:
            raise HTTPException(429, "STT_BUSY") from None
        try:
            async with asyncio.timeout(REQUEST_SECONDS):
                body = bytearray()
                async for chunk in request.stream():
                    if len(body) + len(chunk) > MAX_AUDIO_BYTES:
                        raise HTTPException(413, "STT_AUDIO_TOO_LARGE")
                    body.extend(chunk)
                if not body:
                    raise HTTPException(400, "STT_AUDIO_EMPTY")
                text = await convert_until_disconnect(request, client, bytes(body))
                return {"text": text}
        except (TimeoutError, ClientDisconnect):
            raise HTTPException(504, "STT_TIMED_OUT") from None
        except HTTPException as error:
            # Preserve status mapping for Spring without returning upstream prose.
            LOG.warning("STT request failed: status=%d", error.status_code)
            raise HTTPException(error.status_code, "STT_REQUEST_FAILED") from None
        except Exception:
            LOG.warning("STT request failed: unexpected provider response")
            raise HTTPException(503, "STT_UNAVAILABLE") from None
        finally:
            slots.put_nowait(True)
    return api
