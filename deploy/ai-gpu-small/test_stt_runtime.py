"""Exercise actual pinned Google code through MockTransport, without paid calls."""
import asyncio
import base64
import hashlib
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch

import httpx
from fastapi import FastAPI
from fastapi.testclient import TestClient

import stt_runtime as runtime
from stt_vendor import mp4_faststart, transcribe
from stt_vendor.config import Settings

TOKEN = "test-only-" + "x" * 64
HEADERS = {"x-coach-service-token": TOKEN, "content-type": "audio/mp4"}


def app(client=None):
    api = FastAPI()
    @api.get("/existing")
    def existing():
        return {"preserved": True}
    if client is None:
        return runtime.attach_stt(api, TOKEN, {})
    with patch.object(runtime, "make_transcriber", return_value=client):
        return runtime.attach_stt(api, TOKEN, {"COACH_STT_ENABLED": "true"})


class RouteTests(unittest.TestCase):
    def test_disabled_is_authenticated_and_never_initializes_provider(self):
        with patch.object(runtime, "make_transcriber") as factory, TestClient(app()) as client:
            self.assertEqual(client.post("/backend/coach/transcribe").status_code, 401)
            self.assertEqual(client.post("/backend/coach/transcribe", headers={"x-service-token": TOKEN}).status_code, 401)
            self.assertEqual(client.post("/backend/coach/transcribe", headers=HEADERS).status_code, 503)
            self.assertEqual(client.get("/existing").json(), {"preserved": True})
            factory.assert_not_called()

    def test_no_adc_override_or_nonliteral_flag(self):
        for environment in ({"COACH_STT_ENABLED": "TRUE"}, {"GOOGLE_APPLICATION_CREDENTIALS": "/tmp/x"},
                            {"COACH_STT_MODEL": "other"}, {"COACH_STT_LOCATION": "other"},
                            {"COACH_STT_TIMEOUT_SECONDS": "100"}):
            with self.subTest(environment=environment), self.assertRaises(ValueError):
                runtime.attach_stt(FastAPI(), TOKEN, environment)

    def test_whole_text_and_body_rejections_do_not_call_provider(self):
        provider = SimpleNamespace(transcribe=AsyncMock(return_value="가" * 2100))
        with TestClient(app(provider)) as client:
            for headers, body, status in (({}, b"audio", 401),
                    ({**HEADERS, "content-type": "application/json"}, b"audio", 415),
                    (HEADERS, b"", 400), (HEADERS, b"x" * (runtime.MAX_AUDIO_BYTES + 1), 413)):
                self.assertEqual(client.post("/backend/coach/transcribe", headers=headers, content=body).status_code, status)
            provider.transcribe.assert_not_called()
            response = client.post("/backend/coach/transcribe", headers=HEADERS, content=b"audio")
            self.assertEqual(response.json(), {"text": "가" * 2100})
            provider.transcribe.assert_awaited_once_with(b"audio")

    def test_unexpected_provider_failure_is_redacted(self):
        provider = SimpleNamespace(transcribe=AsyncMock(side_effect=ValueError("private sentinel")))
        with TestClient(app(provider)) as client, self.assertLogs(runtime.LOG, level="WARNING") as logs:
            response = client.post("/backend/coach/transcribe", headers=HEADERS, content=b"audio")
        self.assertEqual(response.status_code, 503)
        self.assertNotIn("private sentinel", response.text + str(logs.output))

    def test_enabled_initialization_has_fixed_project_url_and_no_provider_request(self):
        with patch.object(runtime, "read_account", return_value={"project_id": "synthetic-project"}), \
             patch("google.auth.jwt.Credentials.from_service_account_info", return_value="signed") as signing:
            client = runtime.make_transcriber()
        self.assertEqual(client._account, ("signed", "https://speech.googleapis.com/v2/projects/synthetic-project/locations/global/recognizers/_:recognize"))
        self.assertEqual(signing.call_args.kwargs, {"audience": "https://speech.googleapis.com/"})
        self.assertEqual((client.settings.stt_model, client.settings.stt_timeout_seconds), ("long", 30))
        with patch.object(runtime, "read_account", side_effect=ValueError("private sentinel")):
            with self.assertRaises(ValueError) as error:
                runtime.make_transcriber()
            self.assertNotIn("private sentinel", str(error.exception))


class ConcurrentRoutes(unittest.IsolatedAsyncioTestCase):
    async def test_two_inflight_third_rejected_and_slots_return_after_cancel(self):
        entered = asyncio.Queue()
        release = asyncio.Event()
        async def convert(body):
            entered.put_nowait(True)
            await release.wait()
            return "완료"
        provider = SimpleNamespace(transcribe=convert)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app(provider)), base_url="http://test") as client:
            async def send():
                return await client.post("/backend/coach/transcribe", headers=HEADERS, content=b"audio")
            first = asyncio.create_task(send())
            second = asyncio.create_task(send())
            await asyncio.wait_for(entered.get(), 1)
            await asyncio.wait_for(entered.get(), 1)
            self.assertEqual((await send()).status_code, 429)
            first.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await first
            release.set()
            self.assertEqual((await second).status_code, 200)
            self.assertEqual((await send()).status_code, 200)

    async def test_total_deadline_cancels_provider_and_releases_slot(self):
        provider = SimpleNamespace(transcribe=AsyncMock(side_effect=lambda body: None))
        cancelled = asyncio.Event()
        async def slow(body):
            try:
                await asyncio.sleep(10)
            finally:
                cancelled.set()
        provider.transcribe = slow
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app(provider)), base_url="http://test") as client:
            with patch.object(runtime, "REQUEST_SECONDS", 0.01):
                response = await client.post("/backend/coach/transcribe", headers=HEADERS, content=b"audio")
            self.assertEqual(response.status_code, 504)
            self.assertTrue(cancelled.is_set())
            provider.transcribe = AsyncMock(return_value="재시도")
            self.assertEqual((await client.post("/backend/coach/transcribe", headers=HEADERS, content=b"a")).status_code, 200)


class ProviderTests(unittest.IsolatedAsyncioTestCase):
    def provider(self, handler):
        client = transcribe.GoogleTranscriber(Settings(), transport=httpx.MockTransport(handler))
        def sign(request, method, url, headers):
            headers["Authorization"] = "Bearer test-only"
        client._account = SimpleNamespace(before_request=sign), "https://speech.googleapis.com/v2/projects/test/locations/global/recognizers/_:recognize"
        return client

    async def test_real_google_payload_and_multi_segment_transcript(self):
        calls = []
        def handle(request):
            calls.append(request)
            body = json.loads(request.content)
            self.assertEqual(body["config"], {"autoDecodingConfig": {}, "languageCodes": ["ko-KR"], "model": "long"})
            self.assertEqual(base64.b64decode(body["content"]), b"test-only-audio")
            return httpx.Response(200, json={"results": [{"alternatives": [{"transcript": " 첫 질문 "}]},
                                                        {"alternatives": [{"transcript": "후속 질문"}]}]})
        self.assertEqual(await self.provider(handle).transcribe(b"test-only-audio"), "첫 질문 후속 질문")
        self.assertEqual(len(calls), 1)

    async def test_provider_errors_mapped_once_without_retries(self):
        for remote, expected in ((400, 400), (401, 503), (403, 503), (429, 503), (500, 503), (200, 422)):
            calls = []
            def handle(request):
                calls.append(request)
                return httpx.Response(remote, json={"error": {"status": "TEST", "message": "private sentinel"}})
            with self.subTest(remote=remote):
                with self.assertRaises(transcribe.HTTPException) as error:
                    await self.provider(handle).transcribe(b"audio")
                self.assertEqual(error.exception.status_code, expected)
                self.assertNotIn("private sentinel", error.exception.detail)
                self.assertEqual(len(calls), 1)

    async def test_timeout_and_malformed_payload(self):
        for handler, status in ((lambda r: httpx.Response(200, json={"results": 42}), 503),
                                (lambda r: httpx.Response(200, content=b"not-json"), 503)):
            with self.assertRaises(transcribe.HTTPException) as error:
                await self.provider(handler).transcribe(b"audio")
            self.assertEqual(error.exception.status_code, status)
        def timeout(request):
            raise httpx.ReadTimeout("synthetic")
        with self.assertRaises(transcribe.HTTPException) as error:
            await self.provider(timeout).transcribe(b"audio")
        self.assertEqual(error.exception.status_code, 504)


class SourceIntegrity(unittest.TestCase):
    def test_pinned_upstream_modules_not_agent_refactor(self):
        for module, digest in ((transcribe, "651ca0c05465f820e4ff9eb6cf1397746d3139ccaee0aae73bd3628d28e05316"),
                               (mp4_faststart, "422420ec9b7819360015ae30b236ebc8ee414cacfc5d5a4985efcd1cbfb78f55")):
            self.assertEqual(hashlib.sha256(Path(module.__file__).read_bytes()).hexdigest(), digest)
        self.assertFalse((Path(transcribe.__file__).parent / "main.py").exists())


@unittest.skipUnless(os.name == "posix", "Linux file contract")
class CredentialFiles(unittest.TestCase):
    def test_regular_private_bounded_file_and_malformed_accounts(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "account"
            payload = {"type": "service_account", "project_id": "synthetic-project"}
            path.write_text(json.dumps(payload))
            path.chmod(0o400)
            real_stat = os.stat(path)
            info = SimpleNamespace(st_mode=real_stat.st_mode, st_size=real_stat.st_size,
                                   st_uid=10001, st_gid=10001)
            # CI contract job runs as a different UID; only ownership is simulated.
            with patch.object(runtime.os, "fstat", return_value=info):
                self.assertEqual(runtime.read_account(path), payload)
                for field, value in (("st_uid", 0), ("st_gid", 0), ("st_mode", 0o100644), ("st_size", 16385)):
                    old = getattr(info, field)
                    setattr(info, field, value)
                    with self.assertRaises(ValueError):
                        runtime.read_account(path)
                    setattr(info, field, old)
            link = Path(directory) / "link"
            link.symlink_to(path)
            with self.assertRaises(OSError):
                runtime.read_account(link)
            path.chmod(0o600)
            for data in (b"invalid", b"[]", b'{"type":"service_account","project_id":"../other"}', b"x" * 16385):
                path.write_bytes(data)
                info.st_size = len(data)
                with patch.object(runtime.os, "fstat", return_value=info), self.assertRaises(ValueError):
                    runtime.read_account(path)

    def test_synthetic_key_signs_locally_without_token_exchange(self):
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.hazmat.primitives import serialization
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        account = {"type": "service_account", "project_id": "synthetic-project",
                   "client_email": "test@example.invalid", "token_uri": "https://example.invalid/token",
                   "private_key": key.private_bytes(serialization.Encoding.PEM,
                       serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()}
        with patch.object(runtime, "read_account", return_value=account):
            client = runtime.make_transcriber()
        headers = {}
        credentials, url = client._account
        credentials.before_request(None, "POST", url, headers)
        self.assertTrue(headers["authorization"].startswith("Bearer "))


if __name__ == "__main__":
    unittest.main()
