import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("ec2_preflight", ROOT / "scripts/ci/ec2-preflight.py")
PREFLIGHT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREFLIGHT)
COMMAND_ID = "00000000-0000-0000-0000-000000000032"
CONTEXT = {
    "GITHUB_EVENT_NAME": "workflow_dispatch",
    "GITHUB_REPOSITORY": "Kau-BugFree/hobby-tree-Backend",
    "GITHUB_REF": "refs/heads/develop",
}


class PreflightTests(unittest.TestCase):
    def setUp(self):
        """Isolate CI context, clock and every subprocess from the real host."""
        self.clock = 0
        # Successful mocks must not write a synthetic result to the real CI summary.
        self.context = patch.dict(os.environ, {**CONTEXT, "GITHUB_STEP_SUMMARY": ""})
        self.context.start()
        self.addCleanup(self.context.stop)
        self.timer = patch.object(PREFLIGHT.time, "monotonic", side_effect=lambda: self.clock)
        self.sleeper = patch.object(PREFLIGHT.time, "sleep", side_effect=self.advance)
        self.timer.start()
        self.sleeper.start()
        self.addCleanup(self.timer.stop)
        self.addCleanup(self.sleeper.stop)
        # All subprocess execution is mocked: these tests cannot contact AWS.
        self.runner = patch.object(PREFLIGHT.subprocess, "run")
        self.run = self.runner.start()
        self.addCleanup(self.runner.stop)

    def advance(self, seconds):
        """Advance virtual time without sleeping or making network requests."""
        self.clock += seconds

    @staticmethod
    def response(value):
        """Encode a synthetic successful AWS CLI response."""
        return subprocess.CompletedProcess([], 0, json.dumps(value), "")

    def invoke(self, responses):
        """Run the entry point against mocked AWS responses and capture logs."""
        self.run.side_effect = [self.response(COMMAND_ID), *responses]
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = PREFLIGHT.main()
        return code, stdout.getvalue() + stderr.getvalue()

    def test_success_requires_zero_response_and_uses_fixed_targets(self):
        """A valid success uses only the fixed target and disables send retries."""
        with patch.dict(os.environ, AWS_MAX_ATTEMPTS="9"):
            code, output = self.invoke([self.response({"Status": "Success", "ResponseCode": 0})])
        self.assertEqual(code, 0)
        self.assertIn("PASS:", output)
        send = self.run.call_args_list[0].args[0]
        self.assertIn(PREFLIGHT.INSTANCE_ID, send)
        self.assertIn(PREFLIGHT.DOCUMENT, send)
        self.assertEqual(send[send.index("--document-version") + 1], "1")
        self.assertEqual(send[send.index("--region") + 1], "ap-northeast-2")
        self.assertNotIn("--parameters", send)
        self.assertEqual(self.run.call_args_list[0].kwargs["env"]["AWS_MAX_ATTEMPTS"], "1")
        query = self.run.call_args_list[1].args[0]
        self.assertIn("{Status:Status,ResponseCode:ResponseCode}", query)
        self.assertEqual(self.run.call_args_list[1].kwargs["env"]["AWS_MAX_ATTEMPTS"], "2")

    def test_eventual_consistency_then_pending_delayed_running_success(self):
        """Transient visibility and running states eventually reach success."""
        missing = subprocess.CompletedProcess([], 255, "", "An error (InvocationDoesNotExist)")
        responses = [missing] + [self.response({"Status": status, "ResponseCode": -1})
                                 for status in ("Pending", "Delayed", "InProgress", "Cancelling")]
        responses.append(self.response({"Status": "Success", "ResponseCode": 0}))
        self.assertEqual(self.invoke(responses)[0], 0)
        self.assertEqual(self.clock, 25)

    def test_command_id_is_flushed_before_polling_can_be_interrupted(self):
        """Cancellation while polling must leave the received ID in the log."""
        class BufferedOutput(io.StringIO):
            visible = ""

            def flush(self):
                """Make buffered text visible to the simulated CI log."""
                self.visible = self.getvalue()

        output = BufferedOutput()
        self.run.side_effect = [self.response(COMMAND_ID), KeyboardInterrupt()]
        with contextlib.redirect_stdout(output), self.assertRaises(KeyboardInterrupt):
            PREFLIGHT.execute()
        self.assertIn(COMMAND_ID, output.visible)

    def test_cancellation_during_id_validation_still_records_valid_id(self):
        """Unwinding after receipt must flush the ID before propagating cancellation."""
        original_match = PREFLIGHT.re.fullmatch
        self.run.side_effect = [self.response(COMMAND_ID)]
        with patch.object(PREFLIGHT.re, "fullmatch", side_effect=[
            KeyboardInterrupt(), original_match(r".+", COMMAND_ID),
        ]), patch("builtins.print") as log, self.assertRaises(KeyboardInterrupt):
            PREFLIGHT.execute()
        log.assert_called_once_with(f"SSM Command ID: {COMMAND_ID}", flush=True)
        self.assertEqual(self.run.call_count, 1)

    def test_request_time_is_included_in_the_polling_deadline(self):
        """Slow reads and sleeps share one deadline, including the final call cap."""
        def slow_response(args, **kwargs):
            """Charge virtual time for each read while preserving the send response."""
            if args[2] == "send-command":
                return self.response(COMMAND_ID)
            self.advance(10)
            return self.response({"Status": "InProgress", "ResponseCode": -1})

        self.run.side_effect = slow_response
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(PREFLIGHT.main(), 1)
        self.assertEqual(self.clock, 240)
        self.assertEqual(self.run.call_count, 17)
        self.assertEqual(self.run.call_args.kwargs["timeout"], 15)

    def test_success_after_old_waiter_limit(self):
        """A 125-second success must outlive the old short waiter limit."""
        pending = self.response({"Status": "InProgress", "ResponseCode": -1})
        code, _ = self.invoke([pending] * 25 + [self.response({"Status": "Success", "ResponseCode": 0})])
        self.assertEqual(code, 0)
        self.assertEqual(self.clock, 125)

    def test_success_response_at_or_after_absolute_deadline_is_rejected(self):
        """Accept a pre-deadline success but reject on-time-boundary or late returns."""
        for elapsed, expected in ((239, 0), (240, 1), (241, 1)):
            with self.subTest(elapsed=elapsed):
                self.clock = 0
                self.run.reset_mock()

                def delayed_success(args, **kwargs):
                    """Emulate an unexpectedly late CLI return without real waiting."""
                    if args[2] == "send-command":
                        return self.response(COMMAND_ID)
                    self.advance(elapsed)
                    return self.response({"Status": "Success", "ResponseCode": 0})

                self.run.side_effect = delayed_success
                with contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
                    self.assertEqual(PREFLIGHT.main(), expected)
                self.assertEqual(self.run.call_count, 2)
                if expected:
                    self.assertNotIn("PASS:", out.getvalue())
                    self.assertIn("deadline exceeded", err.getvalue())

    def test_terminal_failures_and_nonzero_success_fail(self):
        """Terminal failures and inconsistent success codes fail the CI job."""
        for status in ("Failed", "TimedOut", "Cancelled", "Success"):
            with self.subTest(status=status):
                self.assertEqual(self.invoke([self.response({"Status": status, "ResponseCode": 1})])[0], 1)

    def test_pending_forever_is_bounded_without_cancelling_remote_command(self):
        """A perpetual pending invocation stops at the documented 240 seconds."""
        pending = self.response({"Status": "InProgress", "ResponseCode": -1})
        self.run.side_effect = lambda args, **kwargs: self.response(COMMAND_ID) if args[2] == "send-command" else pending
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(PREFLIGHT.main(), 1)
        self.assertEqual(PREFLIGHT.WAIT_SECONDS, 240)
        self.assertEqual(self.clock, 240)
        self.assertEqual(self.run.call_count, 1 + PREFLIGHT.WAIT_SECONDS // PREFLIGHT.POLL_SECONDS)
        self.assertTrue(all(call.args[0][2] in {"send-command", "get-command-invocation"}
                            for call in self.run.call_args_list))

    def test_invalid_invocation_results_fail_closed(self):
        """Unknown statuses and noninteger codes cannot be accepted as success."""
        values = [None, [], {}, {"Status": [], "ResponseCode": 0},
                  {"Status": "Unknown", "ResponseCode": 0},
                  {"Status": "Success", "ResponseCode": "0"},
                  {"Status": "Success", "ResponseCode": False}]
        for value in values:
            with self.subTest(value=value):
                self.assertEqual(self.invoke([self.response(value)])[0], 1)

    def test_invalid_command_ids_do_not_poll(self):
        """Malformed or injectable IDs are rejected without polling or ID logging."""
        for value in (None, {}, "None", "", "untrusted\n::error::injection"):
            with self.subTest(value=value):
                self.run.reset_mock()
                self.run.side_effect = [self.response(value)]
                with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()) as output:
                    self.assertEqual(PREFLIGHT.main(), 1)
                self.assertNotIn("SSM Command ID:", output.getvalue())
                self.assertEqual(self.run.call_count, 1)

    def test_send_errors_are_not_retried_or_logged(self):
        """Submission failure neither repeats the send nor leaks CLI output."""
        self.run.side_effect = [subprocess.CompletedProcess([], 255, "sensitive-output", "sensitive-error")]
        with contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
            self.assertEqual(PREFLIGHT.main(), 1)
        self.assertNotIn("sensitive", out.getvalue() + err.getvalue())
        self.assertEqual(self.run.call_count, 1)

    def test_access_denied_is_not_treated_as_eventual_consistency(self):
        """Permission errors fail immediately instead of being retried as visibility."""
        error = subprocess.CompletedProcess([], 255, "", "An error (AccessDeniedException): sensitive")
        code, output = self.invoke([error])
        self.assertEqual(code, 1)
        self.assertNotIn("sensitive", output)
        self.assertEqual(self.clock, 0)

    def test_cli_timeout_and_missing_binary_fail_without_output(self):
        """CLI startup and timeout errors expose only sanitized diagnostics."""
        for error in (FileNotFoundError("sensitive"), subprocess.TimeoutExpired("aws", 30, output="sensitive")):
            with self.subTest(error=type(error).__name__):
                self.run.side_effect = error
                with contextlib.redirect_stderr(io.StringIO()) as out:
                    self.assertEqual(PREFLIGHT.main(), 1)
                self.assertNotIn("sensitive", out.getvalue())

    def test_poll_request_timeout_fails_instead_of_reissuing_command(self):
        """A timed-out read cannot trigger a second submission."""
        code, output = self.invoke([subprocess.TimeoutExpired("aws", 30, output="sensitive")])
        self.assertEqual(code, 1)
        self.assertNotIn("sensitive", output)
        self.assertEqual(self.run.call_count, 2)

    def test_invocation_never_becoming_visible_is_bounded(self):
        """Persistent eventual consistency cannot extend the polling budget."""
        missing = subprocess.CompletedProcess([], 255, "", "An error (InvocationDoesNotExist)")
        self.run.side_effect = lambda args, **kwargs: self.response(COMMAND_ID) if args[2] == "send-command" else missing
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(PREFLIGHT.main(), 1)
        self.assertEqual(self.clock, PREFLIGHT.WAIT_SECONDS)

    def test_malformed_json_does_not_leak_response(self):
        """Invalid response JSON is rejected without its contents entering logs."""
        code, output = self.invoke([subprocess.CompletedProcess([], 0, "sensitive malformed JSON", "")])
        self.assertEqual(code, 1)
        self.assertNotIn("sensitive", output)

    def test_remote_output_is_omitted_from_log_and_summary(self):
        """Neither CI logs nor summaries contain remote stdout or stderr."""
        with tempfile.TemporaryDirectory() as tmp:
            summary = Path(tmp) / "summary.md"
            with patch.dict(os.environ, GITHUB_STEP_SUMMARY=str(summary)):
                code, output = self.invoke([self.response({
                    "Status": "Success", "ResponseCode": 0,
                    "StandardOutputContent": "sensitive-out", "StandardErrorContent": "sensitive-err",
                })])
            self.assertEqual(code, 0)
            self.assertNotIn("sensitive", output + summary.read_text(encoding="utf-8"))
            self.assertIn(COMMAND_ID, summary.read_text(encoding="utf-8"))

    def test_wrong_branch_event_or_repository_never_calls_aws(self):
        """Untrusted execution contexts are rejected before any subprocess call."""
        for key, value in (("GITHUB_REF", "refs/heads/main"),
                           ("GITHUB_REF", "refs/heads/deploy/32-ec2-preflight"),
                           ("GITHUB_EVENT_NAME", "pull_request"),
                           ("GITHUB_REPOSITORY", "someone/fork")):
            with self.subTest(key=key, value=value), patch.dict(os.environ, {key: value}):
                self.run.reset_mock()
                with contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(PREFLIGHT.main(), 1)
                self.run.assert_not_called()

    def test_workflow_has_manual_trigger_and_scoped_oidc_permissions(self):
        """The workflow exposes only manual runs and the credential-bearing job."""
        workflow = (ROOT / ".github/workflows/hobbytree-ec2-preflight.yml").read_text(encoding="utf-8")
        self.assertIn("  workflow_dispatch:", workflow)
        self.assertNotIn("pull_request:", workflow)
        self.assertNotIn("pull_request_target:", workflow)
        self.assertEqual(workflow.count("id-token: write"), 1)
        self.assertIn("needs: guard", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("e1253824e5c10ff9df46874f81ed3ec929e19cfd", workflow)
        self.assertNotIn("secrets.", workflow)


if __name__ == "__main__":
    unittest.main()
