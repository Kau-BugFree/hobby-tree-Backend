"""Run against the pinned AI source with a mock SDK transport, never a real key.

PYTHONPATH must include AI src/activity-coach/backend. No Settings.from_env call:
the AI .env and production state are not read. No app startup or database access.
"""
import asyncio
import importlib.util
import json
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch

import anthropic
import httpx2
from app.claude import ClaudeService
from app.config import Settings

spec = importlib.util.spec_from_file_location("launcher", Path(__file__).with_name("coach-entrypoint.py"))
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)
FAKE_KEY = "sk-ant-" + "unit-test-not-a-real-key-" * 3


class HaikuContractTests(unittest.TestCase):
    def configuration(self, enabled=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, value in [("coach_service_token", "local-test-token-" * 4),
                                ("anthropic_api_key", FAKE_KEY)]:
                path = root / name
                path.write_text(value)
                path.chmod(0o400)
            env = {"COACH_LLM_ENABLED": "true" if enabled else "false"}
            launcher.prepare_environment(env, root)
        return Settings(anthropic_api_key=env["ANTHROPIC_API_KEY"],
                        claude_model=env["CLAUDE_MODEL"], claude_effort=env["CLAUDE_EFFORT"],
                        claude_max_tokens=int(env["CLAUDE_MAX_TOKENS"]),
                        claude_timeout_seconds=float(env["CLAUDE_TIMEOUT_SECONDS"]),
                        claude_max_retries=int(env["CLAUDE_MAX_RETRIES"]))

    def call(self, handler, enabled=True):
        async def run():
            async with anthropic.AsyncAnthropic(
                    api_key=FAKE_KEY, max_retries=0,
                    http_client=anthropic.DefaultAsyncHttpxClient(transport=httpx2.MockTransport(handler))) as client:
                service = ClaudeService(self.configuration(enabled), client=client)
                return await service.explain("큰술을 설명해줘", [{"activity_id": "cook-0-2",
                    "title": "계량", "kind": "activity", "excerpt": "큰술은 15mL입니다."}],
                    {"selected_activity_id": "cook-0-2"})
        # A mistakenly unmocked provider call fails before leaving the process.
        with patch.object(socket.socket, "connect", side_effect=AssertionError("Network forbidden")), \
                patch.object(socket, "create_connection", side_effect=AssertionError("Network forbidden")):
            return asyncio.run(run())

    def test_launcher_settings_reach_actual_sdk_and_response_parser(self):
        requests = []
        def handler(request):
            requests.append(request)
            payload = json.loads(request.content)
            self.assertEqual(payload["model"], "claude-haiku-5-5")
            self.assertEqual(payload["max_tokens"], 4096)
            self.assertEqual(payload["output_config"]["effort"], "medium")
            self.assertEqual(payload["output_config"]["format"]["type"], "json_schema")
            self.assertNotIn("fallbacks", payload)
            self.assertNotIn("server-side-fallback", request.headers.get("anthropic-beta", ""))
            self.assertNotIn(FAKE_KEY, request.content.decode() + str(request.url))
            return httpx2.Response(200, json={"id": "msg_test", "type": "message", "role": "assistant",
                "model": "claude-haiku-5-5", "content": [{"type": "text", "text": json.dumps(
                    {"answer": "큰술은 15mL입니다.", "cited_activity_ids": ["cook-0-2"]})}],
                "stop_reason": "end_turn", "stop_sequence": None,
                "usage": {"input_tokens": 10, "output_tokens": 20}})
        result = self.call(handler)
        self.assertEqual(len(requests), 1)
        self.assertEqual(result["status"], "generated")
        self.assertEqual(result["model"], "claude-haiku-5-5")
        self.assertEqual(result["llm_calls"], 1)
        self.assertNotIn(FAKE_KEY, json.dumps(result))

    def test_errors_are_sanitized_and_not_retried(self):
        for status, expected in [(401, "authentication_error"), (403, "authentication_error"),
                                 (429, "rate_limited"), (500, "provider_error")]:
            calls = []
            def handler(request):
                calls.append(request)
                return httpx2.Response(status, json={"type": "error", "error": {
                    "type": "api_error", "message": FAKE_KEY}})
            with self.subTest(status=status):
                result = self.call(handler)
                self.assertEqual(result["status"], expected)
                self.assertEqual(len(calls), 1)
                self.assertNotIn(FAKE_KEY, json.dumps(result))

    def test_timeout_is_not_reported_as_generation(self):
        def handler(request):
            raise httpx2.ReadTimeout(FAKE_KEY, request=request)
        result = self.call(handler)
        self.assertEqual(result["status"], "timeout")
        self.assertIsNone(result["text"])
        self.assertNotIn(FAKE_KEY, json.dumps(result))

    def test_offline_never_reaches_provider(self):
        def handler(request):
            raise AssertionError("Offline mode called provider")
        result = self.call(handler, enabled=False)
        self.assertEqual(result["status"], "not_configured")
        self.assertEqual(result["llm_calls"], 0)


if __name__ == "__main__":
    unittest.main()
