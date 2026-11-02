#!/usr/bin/env python3
"""Submit the fixed deployment document; leave recovery to the locked host agent."""

import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import time

spec = importlib.util.spec_from_file_location("preflight_api", Path(__file__).with_name("ec2-preflight.py"))
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)
DOCUMENT = "HobbyTree-Dev-DeployBackend"
WAIT_SECONDS = 2400
APPROVAL = "no-migrations-backward-compatible-downtime-approved"


def inputs():
    """Validate dispatch acknowledgements and all arguments before AWS access."""
    api.check_context()
    values = {name: os.environ.get(name, "") for name in ("IMAGE_DIGEST", "EXPECTED_CURRENT_DIGEST", "IMAGE_REVISION")}
    if any(not re.fullmatch(r"sha256:[0-9a-f]{64}", values[k]) for k in ("IMAGE_DIGEST", "EXPECTED_CURRENT_DIGEST")):
        raise api.PreflightError("Two immutable SHA256 digests are required.")
    if not re.fullmatch(r"[0-9a-f]{40}", values["IMAGE_REVISION"]):
        raise api.PreflightError("A full image source SHA is required.")
    if any(os.environ.get(k) != "true" for k in ("ACK_DOWNTIME", "ACK_SCHEMA", "ACK_ROLLBACK")):
        raise api.PreflightError("Explicit downtime, schema and rollback acknowledgements required.")
    request = os.environ.get("GITHUB_RUN_ID", "") + "-" + os.environ.get("GITHUB_RUN_ATTEMPT", "")
    if not re.fullmatch(r"[0-9]{1,20}-[0-9]{1,5}", request):
        raise api.PreflightError("Invalid deployment request ID.")
    return values, request


def execute():
    """Submit once and reject any terminal failure, malformed result or late success."""
    values, request = inputs()
    parameters = {"ImageDigest": [values["IMAGE_DIGEST"]], "ExpectedCurrentDigest": [values["EXPECTED_CURRENT_DIGEST"]],
                  "ImageRevision": [values["IMAGE_REVISION"]], "RequestId": [request], "Approval": [APPROVAL]}
    command_id = None
    try:
        command_id = api.aws_json(["send-command", "--instance-ids", api.INSTANCE_ID, "--document-name", DOCUMENT,
                                  "--document-version", "1", "--parameters", json.dumps(parameters),
                                  "--timeout-seconds", "120", "--max-concurrency", "1", "--max-errors", "0",
                                  "--comment", "HobbyTree deployment " + request, "--query", "Command.CommandId"])
        if not isinstance(command_id, str) or not re.fullmatch(r"[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}", command_id):
            raise api.PreflightError("Invalid deployment command ID.")
    finally:
        if isinstance(command_id, str) and re.fullmatch(r"[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}", command_id):
            print("Deployment SSM command ID: " + command_id, flush=True)
    deadline = time.monotonic() + WAIT_SECONDS
    while time.monotonic() < deadline:
        try:
            result = api.aws_json(["get-command-invocation", "--command-id", command_id, "--instance-id", api.INSTANCE_ID,
                                   "--query", "{Status:Status,ResponseCode:ResponseCode}"], timeout=min(30, deadline - time.monotonic()))
        except api.InvocationNotVisible:
            result = {"Status": "Pending", "ResponseCode": -1}
        if time.monotonic() >= deadline:
            break
        if not isinstance(result, dict) or not isinstance(result.get("Status"), str) or type(result.get("ResponseCode")) is not int:
            raise api.PreflightError("Invalid deployment invocation response.")
        status = result["Status"]
        if status == "Success" and result["ResponseCode"] == 0:
            return command_id
        if status not in api.PENDING:
            if status == "Failed" and result["ResponseCode"] == 10:
                raise api.PreflightError("Deployment failed; previous image restored and verified by the host.")
            if status == "Failed" and result["ResponseCode"] == 20:
                raise api.PreflightError("Deployment and rollback failed; manual recovery required before any retry.")
            raise api.PreflightError("Deployment failed. Check the protected host journal; rollback may have run.")
        time.sleep(min(10, max(0, deadline - time.monotonic())))
    raise api.PreflightError("Deployment observation timed out. Do not resubmit until SSM and the host journal are reconciled.")


def main():
    """Write only public identifiers; cancellation never sends a remote cancel."""
    try:
        command = execute()
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with Path(summary).open("a", encoding="utf-8") as output:
                output.write("## EC2 release\n\nCommand: `" + command + "`\n\nImage: `" + os.environ["IMAGE_DIGEST"] + "`\n\nResult: success.\n")
        print("PASS: fixed deployment document completed successfully.")
        return 0
    except api.PreflightError as error:
        print("FAIL: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
