#!/usr/bin/env python3
"""Run only the existing development preflight; never log remote output."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time


REGION = "ap-northeast-2"
INSTANCE_ID = "i-0d71fcf646b7210f2"
DOCUMENT = "HobbyTree-Dev-Preflight"
DOCUMENT_VERSION = "1"
WAIT_SECONDS = 240
POLL_SECONDS = 5
PENDING = {"Pending", "InProgress", "Delayed", "Cancelling"}
FAILED = {"Cancelled", "TimedOut", "Failed"}


class PreflightError(Exception):
    """A safe diagnostic that may be printed without exposing AWS output."""


class InvocationNotVisible(PreflightError):
    """The submitted invocation is not yet visible through the SSM read API."""


def aws_json(arguments, timeout=30):
    """Call the fixed-region CLI with bounded retries and sanitized failures."""
    env = dict(
        os.environ, AWS_PAGER="", AWS_CLI_AUTO_PROMPT="off",
        AWS_MAX_ATTEMPTS="1" if arguments[0] == "send-command" else "2",
    )
    try:
        result = subprocess.run(
            ["aws", "ssm", *arguments, "--region", REGION, "--output", "json",
             "--no-cli-pager", "--cli-connect-timeout", "5", "--cli-read-timeout", "10"],
            capture_output=True, text=True, timeout=timeout, check=False, env=env,
        )
    except (OSError, subprocess.TimeoutExpired) as from_error:
        # Exception text may include command output. Deliberately do not print it.
        raise PreflightError("AWS CLI unavailable or request timed out.") from from_error
    if result.returncode:
        if arguments[0] == "get-command-invocation" and re.search(
            r"\(InvocationDoesNotExist\)", result.stderr
        ):
            raise InvocationNotVisible("SSM invocation is not visible yet.")
        raise PreflightError("AWS SSM request failed; inspect AWS audit records privately.")
    try:
        return json.loads(result.stdout)
    except (ValueError, TypeError) as error:
        raise PreflightError("AWS SSM returned invalid JSON.") from error


def check_context():
    """Reject any caller other than a manual run on the original develop ref."""
    expected = {
        "GITHUB_EVENT_NAME": "workflow_dispatch",
        "GITHUB_REPOSITORY": "Kau-BugFree/hobby-tree-Backend",
        "GITHUB_REF": "refs/heads/develop",
    }
    if any(os.environ.get(key) != value for key, value in expected.items()):
        raise PreflightError("Only manual develop runs in the original repository are allowed.")


def execute():
    """Submit once, record a valid ID even during unwinding, and poll for 240s."""
    check_context()
    command_id = None
    try:
        command_id = aws_json([
            "send-command", "--instance-ids", INSTANCE_ID,
            "--document-name", DOCUMENT, "--document-version", DOCUMENT_VERSION,
            "--comment", "GitHub Actions EC2 preflight",
            "--timeout-seconds", "120", "--max-concurrency", "1", "--max-errors", "0",
            "--query", "Command.CommandId",
        ])
        if not isinstance(command_id, str) or not re.fullmatch(
            r"[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}", command_id
        ):
            raise PreflightError("AWS SSM did not return a valid command ID.")
    finally:
        # Best effort on cancellation after receipt; never log an unvalidated value.
        if isinstance(command_id, str) and re.fullmatch(
            r"[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}", command_id
        ):
            print(f"SSM Command ID: {command_id}", flush=True)
    deadline = time.monotonic() + WAIT_SECONDS
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise PreflightError(
                "SSM result deadline exceeded. The remote command may still be running; "
                "check its status before another manual run."
            )
        try:
            result = aws_json([
                "get-command-invocation", "--command-id", command_id,
                "--instance-id", INSTANCE_ID,
                "--query", "{Status:Status,ResponseCode:ResponseCode}",
            ], timeout=min(30, remaining))
        except InvocationNotVisible:
            result = {"Status": "Pending", "ResponseCode": -1}
        if not isinstance(result, dict):
            raise PreflightError("AWS SSM returned an invalid invocation result.")
        status, code = result.get("Status"), result.get("ResponseCode")
        if not isinstance(status, str) or status not in PENDING | FAILED | {"Success"} or type(code) is not int:
            raise PreflightError("AWS SSM returned an unknown status or response code.")
        if status == "Success":
            if code != 0:
                raise PreflightError("SSM reported Success with a nonzero response code.")
            return command_id
        if status in FAILED:
            raise PreflightError(f"SSM preflight failed: status={status}, response_code={code}.")
        time.sleep(min(POLL_SECONDS, max(0, deadline - time.monotonic())))


def main():
    """Return a CI exit code and publish only a sanitized success summary."""
    try:
        command_id = execute()
    except PreflightError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print("PASS: GitHub to EC2 SSM preflight completed (Success, response_code=0).")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with Path(summary).open("a", encoding="utf-8") as output:
            output.write(
                f"## EC2 Preflight\n\nCommand: `{command_id}`\n\n"
                "Result: `Success`, response code: `0`.\n\n"
                "Remote stdout/stderr is deliberately omitted.\n"
            )
    return 0


if __name__ == "__main__":
    sys.exit(main())
