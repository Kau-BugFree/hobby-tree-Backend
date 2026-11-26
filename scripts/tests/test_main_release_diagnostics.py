"""Exercise the CI entry point without GitHub/AWS access or deployment side effects."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('diagnostic_gate', ROOT / 'scripts/ci/main-release.py')
C = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(C)
SECRET = 'SYNTHETIC-PRIVATE-TOKEN-do-not-log'
COMMAND = '00000000-0000-0000-0000-000000000123'


class DiagnosticTests(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        temp = self.stack.enter_context(tempfile.TemporaryDirectory())
        self.manifest = dict(source_sha='a'*40, run_id='123', attempt='1', image_id='sha256:'+'b'*64,
                             sql_set_sha256='c'*64, migrations=[{}]*33)
        self.path = Path(temp, 'hobbytree-release/artifact/release.json')
        self.path.parent.mkdir(parents=True)
        self.path.write_text(json.dumps(self.manifest), encoding='utf-8')
        self.env = dict(RUNNER_TEMP=temp, GITHUB_SHA='a'*40, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1',
                        IMAGE_DIGEST='sha256:'+'d'*64, MANIFEST_SHA=hashlib.sha256(self.path.read_bytes()).hexdigest(),
                        HOBBYTREE_MAIN_RELEASE_ENABLED='true', GH_TOKEN=SECRET)
        self.stack.enter_context(patch.dict(os.environ, self.env, clear=True))
        self.stack.enter_context(patch.object(sys, 'argv', ['main-release.py', 'deploy']))
        self.context = self.stack.enter_context(patch.object(C.RELEASE, 'context'))
        self.github = self.stack.enter_context(patch.object(C.RELEASE.PUBLISH, 'api_json', side_effect=self.api))
        self.cli = self.stack.enter_context(patch.object(C.API.subprocess, 'run'))
        self.clock = 0
        self.stack.enter_context(patch.object(C.time, 'monotonic', side_effect=lambda: self.clock))
        self.stack.enter_context(patch.object(C.time, 'sleep', side_effect=self.advance))

    def advance(self, seconds):
        self.clock += seconds

    def api(self, path, target):
        sha, head = 'a'*40, 'e'*40
        if path == 'environments/' + C.ENVIRONMENT:
            return {'name': C.ENVIRONMENT, 'can_admins_bypass': False, 'protection_rules': [
                {'type': 'required_reviewers', 'reviewers': [{'type': 'User', 'reviewer': {'login': 'rladnqls14'}}]}],
                'deployment_branch_policy': {'protected_branches': False, 'custom_branch_policies': True}}
        if 'deployment-branch-policies' in path:
            return {'total_count': 1, 'branch_policies': [{'name': 'main', 'type': 'branch'}]}
        if path == 'actions/oidc/customization/sub':
            return {'use_default': True, 'use_immutable_subject': True,
                    'sub_claim_prefix': C.RELEASE.TARGET['oidc_sub_prefix']}
        if path == 'git/ref/heads/main':
            return {'object': {'sha': sha}}
        if path == 'commits/' + sha:
            return {'parents': [{'sha': 'f'*40}, {'sha': head}]}
        if path.endswith('/pulls?per_page=100'):
            return [{'merged_at': 'fixture', 'merge_commit_sha': sha, 'base': {'ref': 'main'},
                     'head': {'ref': 'develop', 'sha': head, 'repo': {'full_name': C.RELEASE.REPOSITORY}}}]
        if '/check-runs?' in path:
            return {'check_runs': [{'name': name, 'head_sha': head, 'conclusion': 'success',
                                   'app': {'slug': 'github-actions'}} for name in (
                                       'Spotless Check', 'Docker amd64 and PostgreSQL 17', 'Flyway PostgreSQL 17 migrations')]}
        raise AssertionError('Unexpected fixture API path')

    def invoke(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = C.main()
        text = out.getvalue() + err.getvalue()
        self.assertNotIn(SECRET, text)
        self.assertNotIn('Traceback', text)
        diagnostic = next((json.loads(line.split(': ', 1)[1]) for line in err.getvalue().splitlines()
                           if line.startswith('MAIN_RELEASE_GATE_FAILED: ')), None)
        return code, text, diagnostic

    def response(self, value):
        return subprocess.CompletedProcess([], 0, json.dumps(value), '')

    def test_github_http_failures_identify_exact_read_without_sending_to_aws(self):
        paths = {'environments/' + C.ENVIRONMENT: 'environment_read',
                 'environments/' + C.ENVIRONMENT + '/deployment-branch-policies?per_page=100': 'environment_branches',
                 'actions/oidc/customization/sub': 'oidc', 'git/ref/heads/main': 'latest_main',
                 'commits/'+'a'*40: 'provenance_commit',
                 'commits/'+'a'*40+'/pulls?per_page=100': 'provenance_main_pr',
                 'commits/'+'e'*40+'/check-runs?filter=latest&per_page=100': 'provenance_checks'}
        for failed, expected in paths.items():
            def read(path, target):
                if path == failed:
                    raise HTTPError('https://example.invalid/'+SECRET, 403, SECRET, {'Authorization': SECRET}, io.BytesIO(SECRET.encode()))
                return self.api(path, target)
            with self.subTest(stage=expected):
                self.github.side_effect = read
                code, _, diagnostic = self.invoke()
                self.assertEqual(code, 1)
                self.assertEqual(diagnostic, {'stage': expected, 'kind': 'github_http', 'http_status': 403})
                self.cli.assert_not_called()

    def test_manifest_hash_decode_identity_and_payload_failure_never_send(self):
        cases = [('artifact_manifest', lambda: os.environ.update(MANIFEST_SHA='0'*64)),
                 ('artifact_decode', lambda: self.write_manifest(SECRET)),
                 ('manifest_identity', lambda: os.environ.update(GITHUB_RUN_ATTEMPT='2')),
                 ('request_payload', lambda: os.environ.update(IMAGE_DIGEST=SECRET))]
        for expected, mutate in cases:
            with self.subTest(stage=expected), patch.dict(os.environ, self.env):
                self.write_manifest(json.dumps(self.manifest))
                mutate()
                code, _, diagnostic = self.invoke()
                self.assertEqual(code, 1)
                self.assertEqual(diagnostic['stage'], expected)
                self.cli.assert_not_called()

    def write_manifest(self, text):
        self.path.write_text(text, encoding='utf-8')
        os.environ['MANIFEST_SHA'] = hashlib.sha256(self.path.read_bytes()).hexdigest()

    def test_aws_failure_is_allowlisted_and_send_is_never_retried(self):
        for aws_code, expected in [('AccessDeniedException', 'AccessDeniedException'),
                                   ('InvalidParameters', 'InvalidParameters'), (SECRET, 'CLI_EXIT_ERROR')]:
            with self.subTest(code=expected):
                self.cli.reset_mock()
                self.cli.return_value = subprocess.CompletedProcess([], 254, SECRET,
                    f'An error occurred ({aws_code}) when calling the SendCommand operation: {SECRET}')
                code, text, diagnostic = self.invoke()
                self.assertEqual(code, 1)
                self.assertEqual(diagnostic, {'stage': 'ssm_send', 'kind': 'ssm', 'code': expected})
                self.cli.assert_called_once()
                self.assertEqual(self.cli.call_args.kwargs['env']['AWS_MAX_ATTEMPTS'], '1')
                self.assertNotIn('MAIN_RELEASE_COMMAND=', text)

    def test_cli_missing_timeout_and_invalid_json_remain_safe(self):
        for failure, expected in [(FileNotFoundError(SECRET), 'CLI_UNAVAILABLE'),
                                  (subprocess.TimeoutExpired([SECRET], 1, output=SECRET, stderr=SECRET), 'CLI_TIMEOUT'),
                                  (self.response(None), None)]:
            if expected is None:
                self.cli.side_effect = None
                self.cli.return_value = subprocess.CompletedProcess([], 0, SECRET, SECRET)
                expected = 'INVALID_JSON'
            else:
                self.cli.side_effect = failure
            code, _, diagnostic = self.invoke()
            self.assertEqual(code, 1)
            self.assertEqual(diagnostic, {'stage': 'ssm_send', 'kind': 'ssm', 'code': expected})

    def test_poll_failure_preserves_command_id_and_does_not_resubmit(self):
        for result in ({'Status': 'Failed', 'ResponseCode': 1}, {'Status': SECRET}, []):
            with self.subTest(result=result):
                self.cli.reset_mock()
                self.cli.side_effect = [self.response(COMMAND), self.response(result)]
                code, text, diagnostic = self.invoke()
                self.assertEqual(code, 1)
                self.assertEqual(diagnostic['stage'], 'ssm_observe')
                self.assertIn('MAIN_RELEASE_COMMAND='+COMMAND, text)
                self.assertEqual(len(self.cli.call_args_list), 2)
                self.assertEqual(self.cli.call_args_list[0].args[0][2], 'send-command')
                self.assertEqual(self.cli.call_args_list[1].args[0][2], 'get-command-invocation')

    def test_pending_visibility_and_success_keep_existing_poll_semantics(self):
        invisible = subprocess.CompletedProcess([], 1, '',
            f'An error occurred (InvocationDoesNotExist) when calling the GetCommandInvocation operation: {SECRET}')
        self.cli.side_effect = [self.response(COMMAND), invisible,
                               self.response({'Status': 'InProgress', 'ResponseCode': -1}),
                               self.response({'Status': 'Success', 'ResponseCode': 0})]
        code, text, diagnostic = self.invoke()
        self.assertEqual(code, 0)
        self.assertIsNone(diagnostic)
        self.assertIn('MAIN_RELEASE_COMMAND='+COMMAND, text)
        self.assertEqual(self.clock, 20)

    def test_network_and_unknown_exception_messages_are_not_logged(self):
        for error, kind in [(URLError(SECRET), 'network'), (RuntimeError(SECRET), 'unexpected'),
                            (KeyError(SECRET), 'missing_field')]:
            self.context.side_effect = error
            code, _, diagnostic = self.invoke()
            self.assertEqual(code, 1)
            self.assertEqual(diagnostic, {'stage': 'release_context', 'kind': kind})
            self.cli.assert_not_called()

    def test_invalid_command_id_and_poll_deadline_do_not_resend(self):
        self.cli.return_value = self.response(SECRET)
        code, _, diagnostic = self.invoke()
        self.assertEqual(code, 1)
        self.assertEqual(diagnostic['stage'], 'ssm_command_identity')
        self.cli.assert_called_once()
        self.cli.reset_mock()
        def run(args, **kwargs):
            if args[2] == 'send-command':
                return self.response(COMMAND)
            self.clock = 6001
            return self.response({'Status': 'Pending', 'ResponseCode': -1})
        self.cli.side_effect = run
        code, text, diagnostic = self.invoke()
        self.assertEqual(code, 1)
        self.assertEqual(diagnostic['stage'], 'ssm_observation_deadline')
        self.assertIn('MAIN_RELEASE_COMMAND='+COMMAND, text)
        self.assertEqual(len(self.cli.call_args_list), 2)

    def test_arbitrary_http_status_and_ssm_code_are_not_reflected(self):
        for error in (HTTPError(SECRET, SECRET, SECRET, {}, None),
                      C.API.PreflightError(SECRET, diagnostic_code=SECRET)):
            self.context.side_effect = error
            code, _, diagnostic = self.invoke()
            self.assertEqual(code, 1)
            self.assertNotIn('http_status', diagnostic)
            self.cli.assert_not_called()

    def test_real_cli_entry_missing_artifact_exits_one_without_traceback_or_secret(self):
        # This fails before GitHub/AWS access; restore only subprocess.run for the child Python.
        with patch.object(C.API.subprocess, 'run', wraps=REAL_RUN):
            self.path.unlink()
            child = subprocess.run([sys.executable, '-B', str(ROOT / 'scripts/ci/main-release.py'), 'deploy'],
                                   env={**os.environ, 'PATH': ''}, capture_output=True, text=True, timeout=10)
        self.assertEqual(child.returncode, 1)
        self.assertNotIn(SECRET, child.stdout + child.stderr)
        self.assertNotIn('Traceback', child.stderr)
        self.assertIn('"stage": "artifact_manifest"', child.stderr)


REAL_RUN = subprocess.run

if __name__ == '__main__':
    unittest.main()
