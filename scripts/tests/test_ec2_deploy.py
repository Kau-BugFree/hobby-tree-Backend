import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import hashlib
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("deployment_client", ROOT / "scripts/ci/ec2-deploy.py")
CLIENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLIENT)
CONTEXT = {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REPOSITORY": "Kau-BugFree/hobby-tree-Backend",
           "GITHUB_REF": "refs/heads/develop", "IMAGE_DIGEST": "sha256:" + "2" * 64,
           "EXPECTED_CURRENT_DIGEST": "sha256:" + "1" * 64, "IMAGE_REVISION": "a" * 40,
           "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1", "GITHUB_STEP_SUMMARY": "",
           "ACK_DOWNTIME": "true", "ACK_SCHEMA": "true", "ACK_ROLLBACK": "true"}
COMMAND = "00000000-0000-0000-0000-000000000034"


class DeployClientTests(unittest.TestCase):
    """Test dispatch and SSM result handling without any AWS or network requests."""

    def setUp(self):
        """Isolate all API calls and CI summary writes."""
        self.clock = 0
        self.context = patch.dict(os.environ, CONTEXT)
        self.context.start()
        self.addCleanup(self.context.stop)
        for target, attribute, arguments in ((CLIENT.api, "aws_json", {}),
                                               (CLIENT.time, "monotonic", {"side_effect": lambda: self.clock}),
                                               (CLIENT.time, "sleep", {"side_effect": self.advance})):
            current = patch.object(target, attribute, **arguments)
            mocked = current.start()
            self.addCleanup(current.stop)
            if attribute == "aws_json":
                self.aws = mocked

    def advance(self, value):
        """Use virtual polling time."""
        self.clock += value

    def invoke(self):
        """Capture sanitized logs for the workflow entry point."""
        with contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
            code = CLIENT.main()
        return code, out.getvalue() + err.getvalue()

    def test_success_uses_fixed_document_version_target_and_safe_json_parameters(self):
        """No caller can choose a target, document, command or mutable image tag."""
        self.aws.side_effect = [COMMAND, {"Status": "Success", "ResponseCode": 0}]
        self.assertEqual(self.invoke()[0], 0)
        arguments = self.aws.call_args_list[0].args[0]
        self.assertIn("HobbyTree-Dev-DeployBackend", arguments)
        self.assertIn(CLIENT.api.INSTANCE_ID, arguments)
        self.assertEqual(arguments[arguments.index("--document-version") + 1], "1")
        params = json.loads(arguments[arguments.index("--parameters") + 1])
        self.assertEqual(params["ImageDigest"], [CONTEXT["IMAGE_DIGEST"]])
        self.assertEqual(params["RequestId"], ["123-1"])
        self.assertNotIn("commands", params)

    def test_wrong_context_or_missing_ack_never_contacts_aws(self):
        """Explicit human release intent is required independently of IAM access."""
        changes = [("GITHUB_REF", "refs/heads/main"), ("GITHUB_EVENT_NAME", "pull_request"),
                   ("GITHUB_REPOSITORY", "someone/fork"), ("ACK_DOWNTIME", "false"),
                   ("ACK_SCHEMA", "false"), ("ACK_ROLLBACK", "false")]
        for key, value in changes:
            with self.subTest(key=key), patch.dict(os.environ, {key: value}):
                self.aws.reset_mock()
                self.assertEqual(self.invoke()[0], 1)
                self.aws.assert_not_called()

    def test_invalid_release_inputs_are_rejected_before_aws(self):
        """Reject mutable tags, shell fragments and invalid audit identifiers."""
        for key, value in (("IMAGE_DIGEST", "latest"), ("EXPECTED_CURRENT_DIGEST", "$(id)"),
                           ("IMAGE_REVISION", "main"), ("GITHUB_RUN_ID", "../audit")):
            with self.subTest(key=key), patch.dict(os.environ, {key: value}):
                self.aws.reset_mock()
                self.assertEqual(self.invoke()[0], 1)
                self.aws.assert_not_called()

    def test_visibility_delay_then_success(self):
        """A newly accepted SSM invocation may take time to become visible."""
        self.aws.side_effect = [COMMAND, CLIENT.api.InvocationNotVisible(),
                                {"Status": "InProgress", "ResponseCode": -1}, {"Status": "Success", "ResponseCode": 0}]
        self.assertEqual(self.invoke()[0], 0)
        self.assertEqual(self.clock, 20)

    def test_failed_or_rolled_back_document_is_not_success(self):
        """A verified rollback still fails the deployment workflow."""
        for result in ({"Status": "Failed", "ResponseCode": 1}, {"Status": "Success", "ResponseCode": 1},
                       {"Status": "TimedOut", "ResponseCode": -1}, {"Status": "Success", "ResponseCode": False}):
            with self.subTest(result=result):
                self.aws.side_effect = [COMMAND, result]
                self.assertEqual(self.invoke()[0], 1)

    def test_rollback_exit_codes_are_reported_without_reading_remote_output(self):
        """Distinguish a verified restoration from a failed recovery using response codes."""
        for code, message in ((10, "previous image restored"), (20, "rollback failed")):
            with self.subTest(code=code):
                self.aws.side_effect = [COMMAND, {"Status": "Failed", "ResponseCode": code}]
                status, output = self.invoke()
                self.assertEqual(status, 1)
                self.assertIn(message, output)
                self.assertNotIn("StandardOutputContent", self.aws.call_args.args[0])

    def test_polling_is_bounded_and_never_resubmits_or_cancels(self):
        """Observer timeout cannot trigger another EC2 command or remote cancellation."""
        self.aws.side_effect = lambda arguments, **kwargs: COMMAND if arguments[0] == "send-command" else {"Status": "InProgress", "ResponseCode": -1}
        self.assertEqual(self.invoke()[0], 1)
        self.assertEqual(self.clock, 2400)
        self.assertEqual(sum(call.args[0][0] == "send-command" for call in self.aws.call_args_list), 1)
        self.assertFalse(any(call.args[0][0] == "cancel-command" for call in self.aws.call_args_list))

    def test_late_success_is_rejected(self):
        """Absolute observation deadline is checked after AWS returns."""
        def late(arguments, **kwargs):
            """Simulate an unexpectedly late successful CLI return."""
            if arguments[0] == "send-command":
                return COMMAND
            self.advance(2401)
            return {"Status": "Success", "ResponseCode": 0}

        self.aws.side_effect = late
        self.assertEqual(self.invoke()[0], 1)

    def test_command_id_is_logged_when_validation_is_interrupted(self):
        """Retain a recovery handle while unwinding after the send response."""
        self.aws.return_value = COMMAND
        original = re.fullmatch
        # Input validation uses five matches before the received ID is checked.
        def match(pattern, value):
            """Interrupt only the first received-ID validation."""
            if value == COMMAND and not getattr(match, "interrupted", False):
                match.interrupted = True
                raise KeyboardInterrupt()
            return original(pattern, value)

        with patch.object(CLIENT.re, "fullmatch", side_effect=match), patch("builtins.print") as log, self.assertRaises(KeyboardInterrupt):
            CLIENT.execute()
        log.assert_called_once_with("Deployment SSM command ID: " + COMMAND, flush=True)


class DeploymentArtifactTests(unittest.TestCase):
    """Check the privilege boundary in generated SSM and IAM artifacts offline."""

    def test_generated_document_and_contract_are_current(self):
        """Script or approved-file edits must invalidate the pinned document."""
        spec = importlib.util.spec_from_file_location("render_release", ROOT / "scripts/deploy/render-artifacts.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for name, expected in module.artifacts().items():
            self.assertEqual(json.loads((ROOT / name).read_text(encoding="utf-8")), expected)

    def test_document_accepts_only_env_interpolated_constrained_parameters(self):
        """No arbitrary shell command or raw parameter interpolation is exposed."""
        doc = json.loads((ROOT / "deploy/ssm/HobbyTree-Dev-DeployBackend.json").read_text(encoding="utf-8"))
        self.assertEqual(doc["schemaVersion"], "2.2")
        self.assertEqual(set(doc["parameters"]), {"ImageDigest", "ExpectedCurrentDigest", "ImageRevision", "RequestId", "Approval"})
        self.assertTrue(all(x["interpolationType"] == "ENV_VAR" and "allowedPattern" in x for x in doc["parameters"].values()))
        shell = "\n".join(doc["mainSteps"][0]["inputs"]["runCommand"])
        self.assertNotIn("{{", shell)
        self.assertIn("sha256sum --check --status", shell)
        self.assertIn("/usr/bin/python3 -I", shell)
        self.assertEqual(doc["mainSteps"][0]["inputs"]["timeoutSeconds"], "2100")

    def test_iam_templates_do_not_grant_mutation_or_secret_permissions(self):
        """GitHub can only send fixed documents; EC2 additions are read-only AWS APIs."""
        github = json.loads((ROOT / "deploy/iam/github-ec2-deployment-policy.json").read_text(encoding="utf-8"))
        statements = github["Statement"]
        self.assertEqual({x["Action"] for x in statements}, {"ssm:SendCommand", "ssm:GetCommandInvocation"})
        send = next(x for x in statements if x["Action"] == "ssm:SendCommand")
        self.assertEqual(len(send["Resource"]), 3)
        self.assertFalse(any("*" in value for value in send["Resource"]))
        ec2 = json.loads((ROOT / "deploy/iam/ec2-release-read-policy.json").read_text(encoding="utf-8"))
        actions = {action for statement in ec2["Statement"] for action in ([statement["Action"]] if isinstance(statement["Action"], str) else statement["Action"])}
        self.assertEqual(actions, {"ecr:GetAuthorizationToken", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer",
                                   "elasticloadbalancing:DescribeTargetGroups", "elasticloadbalancing:DescribeTargetHealth"})

    def test_workflow_only_runs_manually_and_preflight_precedes_release(self):
        """The AWS-bearing path cannot execute in a PR or skip preflight."""
        text = (ROOT / ".github/workflows/hobbytree-ec2-deploy.yml").read_text(encoding="utf-8")
        self.assertNotIn("pull_request:", text)
        self.assertNotIn("pull_request_target:", text)
        self.assertIn("  workflow_dispatch:", text)
        self.assertEqual(text.count("id-token: write"), 1)
        self.assertIn("cancel-in-progress: false", text)
        self.assertLess(text.index("run: python3 scripts/ci/ec2-preflight.py"), text.index("run: python3 scripts/ci/ec2-deploy.py"))
        self.assertNotIn("secrets.", text)

    @unittest.skipUnless(os.name == "posix", "Generated shell pin is executed on Linux CI")
    def test_generated_hash_command_accepts_only_the_pinned_bytes(self):
        """Exercise the real sha256sum boundary without executing the host agent."""
        doc = json.loads((ROOT / "deploy/ssm/HobbyTree-Dev-DeployBackend.json").read_text(encoding="utf-8"))
        command = next(x for x in doc["mainSteps"][0]["inputs"]["runCommand"] if x.startswith("printf "))
        pinned = re.search(r"[0-9a-f]{64}", command).group()
        with tempfile.TemporaryDirectory() as tmp:
            fixture = Path(tmp) / "agent"
            fixture.write_bytes(b"reviewed bytes\n")
            adapted = command.replace(pinned, hashlib.sha256(fixture.read_bytes()).hexdigest())
            adapted = adapted.replace("'/opt/hobbytree/scripts/deploy/ec2-release.py'", shlex.quote(str(fixture)))
            self.assertEqual(subprocess.run(["sh", "-c", adapted], capture_output=True).returncode, 0)
            fixture.write_bytes(b"changed bytes\n")
            self.assertNotEqual(subprocess.run(["sh", "-c", adapted], capture_output=True).returncode, 0)

    @unittest.skipUnless(os.name == "posix", "Real Compose rendering is verified on Linux CI")
    def test_snapshot_preserves_project_directory_and_relative_bind_paths(self):
        """Render the real Compose snapshot without starting containers or contacting AWS."""
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            project = base / "approved-project"
            journal = base / "journal"
            project.mkdir()
            journal.mkdir()
            snapshot = journal / "compose.approved.yml"
            snapshot.write_bytes((ROOT / "compose.ec2.yml").read_bytes())
            settings = base / "settings"
            settings.write_text("BACKEND_IMAGE=example.invalid/backend@sha256:" + "2" * 64 + "\n"
                                "DB_URL=jdbc:postgresql://unused/test\nAUTH_TRUSTED_PROXY_CIDRS=192.0.2.10/32\n"
                                "KAKAO_APP_ID=1\nGOOGLE_CLIENT_ID=ci.apps.googleusercontent.com\n")
            result = subprocess.run(["docker", "compose", "--project-name", "hobbytree", "--project-directory", str(project),
                                     "--env-file", str(settings), "-f", str(snapshot), "config", "--format", "json"],
                                    capture_output=True, env={"PATH": os.environ["PATH"], "HOME": str(base)}, check=True)
            config = json.loads(result.stdout)
            self.assertEqual(config["name"], "hobbytree")
            redis = config["services"]["redis"]
            source = next(x["source"] for x in redis["volumes"] if x["target"] == "/etc/redis/redis.conf")
            self.assertEqual(source, str(project / "deploy/redis.conf"))


if __name__ == "__main__":
    unittest.main()
