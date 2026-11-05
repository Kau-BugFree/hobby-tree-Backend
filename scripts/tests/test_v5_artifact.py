"""Offline publisher failure cases; real original-image inspection runs in AWS-free CI."""

from contextlib import redirect_stdout
from copy import deepcopy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('v5_artifact', ROOT / 'scripts/ci/v5-artifact.py')
V5 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(V5)


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        """Prepare isolated public metadata fixtures and mocked ECR responses."""
        self.expected = V5.lock()
        e = self.expected
        self.run = dict(id=e['run_id'], run_attempt=1, head_sha=e['workflow_sha'], workflow_id=e['workflow_id'],
                        path=e['workflow_path'], event='workflow_dispatch', status='completed', conclusion='success',
                        repository={'id': e['repository_id']}, head_repository={'id': e['repository_id']})
        self.artifact = dict(id=e['artifact_id'], name=e['artifact_name'], size_in_bytes=e['artifact_size'],
                             digest='sha256:' + e['zip_sha256'], expired=False, expires_at='2099-01-01T00:00:00Z',
                             workflow_run=dict(id=e['run_id'], head_sha=e['workflow_sha'],
                                               repository_id=e['repository_id'], head_repository_id=e['repository_id']))
        self.jobs = {'jobs': [dict(id=e['job_id'], status='completed', conclusion='success')]}
        self.inspect = [dict(Id=e['image_id'], Os='linux', Architecture='amd64', Config={
            'User': '10001:10001', 'Labels': {'org.opencontainers.image.revision': e['source_sha']}})]
        self.repo = {'repositories': [dict(registryId=e['account_id'], repositoryName=e['ecr_repository'],
                                           imageTagMutability='IMMUTABLE')]}
        self.absent = {'images': [], 'failures': [dict(failureCode='ImageNotFound', imageId={'imageTag': e['source_sha']})]}

    def metadata(self):
        """Run the provenance gate against the current test fixtures."""
        V5.validate_metadata(self.run, self.artifact, self.jobs, self.expected)

    def fixture(self, directory, members=None):
        """Tiny synthetic bytes for failure tests only; never claim these are the original image."""
        e = deepcopy(self.expected)
        archive = b'offline archive fixture'
        e['archive_sha256'] = hashlib.sha256(archive).hexdigest()
        evidence = {k: e[k] for k in ('source_sha', 'workflow_sha', 'source_tree', 'image_id', 'platform',
                                     'sql_files', 'sql_set_sha256', 'archive_sha256')}
        evidence.update(oci_revision=e['source_sha'], run_id=str(e['run_id']), run_attempt='1',
                        runtime_validation='passed', stable_health_seconds=30, backend_restarts=0,
                        redis_preserved=True, registry_push_performed=False, registry_manifest_digest=None)
        files = members or {'image.tar.gz': archive, 'evidence.json': json.dumps(evidence).encode(),
                            'SHA256SUMS': (e['archive_sha256'] + '  image.tar.gz\n').encode()}
        with zipfile.ZipFile(directory / 'artifact.zip', 'w') as output:
            for name, content in files.items():
                output.writestr(name, content)
        e['zip_sha256'] = V5.sha(directory / 'artifact.zip')
        return e

    def published(self):
        """Construct a synthetic registry manifest solely for offline validation tests."""
        e = self.expected
        raw = json.dumps({'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.manifest.v1+json',
                          'config': {'digest': e['image_id']}, 'layers': []})
        return {'images': [dict(registryId=e['account_id'], repositoryName=e['ecr_repository'],
                               imageId={'imageTag': e['source_sha'], 'imageDigest': 'sha256:' + hashlib.sha256(raw.encode()).hexdigest()},
                               imageManifest=raw)]}

    def test_exact_successful_run_and_artifact_accepted(self):
        """Accept the precise successful source workflow, attempt and original artifact."""
        self.metadata()

    def test_changed_run_attempt_revision_event_or_failure_rejected(self):
        """Reject substitutions of the source execution or its successful result."""
        for key, bad in [('id', 1), ('run_attempt', 2), ('head_sha', self.expected['source_sha']),
                         ('workflow_id', 1), ('path', 'other.yml'), ('event', 'pull_request'), ('conclusion', 'failure')]:
            with self.subTest(key=key), self.assertRaises(V5.VerificationError):
                run = {**self.run, key: bad}
                V5.validate_metadata(run, self.artifact, self.jobs, self.expected)

    def test_fork_and_missing_candidate_success_rejected(self):
        """Reject fork provenance and missing successful candidate jobs."""
        for key in ('repository', 'head_repository'):
            with self.subTest(key=key), self.assertRaises(V5.VerificationError):
                V5.validate_metadata({**self.run, key: {'id': 1}}, self.artifact, self.jobs, self.expected)
        with self.assertRaises(V5.VerificationError):
            V5.validate_metadata(self.run, self.artifact, {'jobs': []}, self.expected)

    def test_missing_substituted_or_expired_artifact_rejected(self):
        """Fail closed on missing, substituted, expired or unrelated artifacts."""
        for changed in ({}, {**self.artifact, 'id': 1}, {**self.artifact, 'digest': 'sha256:' + '0' * 64},
                        {**self.artifact, 'expired': True}, {**self.artifact, 'expires_at': '2000-01-01T00:00:00Z'},
                        {**self.artifact, 'workflow_run': {}}):
            with self.subTest(artifact=changed), self.assertRaises((V5.VerificationError, KeyError)):
                V5.validate_metadata(self.run, changed, self.jobs, self.expected)

    def test_normal_artifact_extraction_and_evidence(self):
        """Accept a consistent offline ZIP, image archive and evidence record."""
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            e = self.fixture(directory)
            V5.extract(directory, e)
            V5.verify_files(directory, e)

    def test_zip_checksum_mismatch_rejected_before_extract(self):
        """Reject modified ZIP bytes before writing extracted artifact members."""
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            e = self.fixture(directory)
            e['zip_sha256'] = '0' * 64
            with self.assertRaises(V5.VerificationError):
                V5.extract(directory, e)
            self.assertFalse((directory / 'image.tar.gz').exists())

    def test_archive_sha256_and_evidence_mutation_rejected(self):
        """Reject image archive changes and altered runtime verification evidence."""
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            e = self.fixture(directory)
            V5.extract(directory, e)
            (directory / 'image.tar.gz').write_bytes(b'modified')
            with self.assertRaises(V5.VerificationError):
                V5.verify_files(directory, e)
            (directory / 'image.tar.gz').write_bytes(b'offline archive fixture')
            evidence = json.loads((directory / 'evidence.json').read_text())
            evidence['runtime_validation'] = 'skipped'
            (directory / 'evidence.json').write_text(json.dumps(evidence))
            with self.assertRaises(V5.VerificationError):
                V5.verify_files(directory, e)

    def test_missing_archive_and_traversal_rejected(self):
        """Reject incomplete artifacts and paths outside the extraction directory."""
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            e = self.fixture(directory, {'../escape': b'untrusted'})
            with self.assertRaises(V5.VerificationError):
                V5.extract(directory, e)
        with tempfile.TemporaryDirectory() as temporary, self.assertRaises(FileNotFoundError):
            V5.verify_files(Path(temporary), self.expected)

    def test_actual_image_id_revision_and_platform(self):
        """Accept the pinned image and reject config ID, architecture or revision drift."""
        V5.verify_inspect(self.inspect, self.expected)
        cases = [('Id', 'sha256:' + '0' * 64), ('Architecture', 'arm64'), ('Os', 'windows')]
        for key, bad in cases:
            with self.subTest(key=key), self.assertRaises(V5.VerificationError):
                V5.verify_inspect([{**self.inspect[0], key: bad}], self.expected)
        changed = deepcopy(self.inspect)
        changed[0]['Config']['Labels']['org.opencontainers.image.revision'] = 'latest-develop'
        with self.assertRaises(V5.VerificationError):
            V5.verify_inspect(changed, self.expected)

    def test_image_id_mismatch_prevents_container_creation(self):
        """Stop after an ID mismatch before creating even a stopped inspection container."""
        with patch.object(V5, 'verify_files'), patch.object(V5, 'command') as command:
            bad = deepcopy(self.inspect)
            bad[0]['Id'] = 'sha256:' + '0' * 64
            command.side_effect = [b'', json.dumps(bad).encode()]
            with self.assertRaises(V5.VerificationError):
                V5.verify_image(Path('unused'), self.expected)
            self.assertEqual(command.call_count, 2)

    def test_v1_to_v5_sql_set_reuses_existing_schema_parser(self):
        """Accept exactly V1 through V5 and reject any added V6 migration."""
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'app.jar'
            e = deepcopy(self.expected)
            with zipfile.ZipFile(jar, 'w') as output:
                for name in e['sql_files']:
                    output.writestr('BOOT-INF/classes/db/migration/' + name, 'SELECT 1;\n')
            e['sql_set_sha256'] = V5.SCHEMA.schema_fingerprint(jar)
            V5.verify_jar(jar, e)
            with zipfile.ZipFile(jar, 'a') as output:
                output.writestr('BOOT-INF/classes/db/migration/V6__unapproved.sql', 'SELECT 2;')
            with self.assertRaisesRegex(V5.VerificationError, 'V6 forbidden'):
                V5.verify_jar(jar, e)

    def test_changed_sql_checksum_rejected(self):
        """Reject modified SQL even when all five migration filenames remain unchanged."""
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'app.jar'
            with zipfile.ZipFile(jar, 'w') as output:
                for name in self.expected['sql_files']:
                    output.writestr('BOOT-INF/classes/db/migration/' + name, 'unapproved SQL')
            with self.assertRaisesRegex(V5.VerificationError, 'checksum'):
                V5.verify_jar(jar, self.expected)

    def test_approval_environment_must_be_protected(self):
        """Reject absent reviewers, bypass privileges and unrestricted environment refs."""
        environment = dict(name=self.expected['environment'], can_admins_bypass=False,
                           protection_rules=[dict(type='required_reviewers', prevent_self_review=True, reviewers=[{'type': 'User'}])],
                           deployment_branch_policy=dict(protected_branches=False, custom_branch_policies=True))
        policies = dict(total_count=1, branch_policies=[dict(name='develop', type='branch')])
        V5.validate_environment(environment, policies, self.expected)
        for changes in ({'protection_rules': []}, {'can_admins_bypass': True}, {'deployment_branch_policy': None}):
            with self.subTest(changes=changes), self.assertRaises(V5.VerificationError):
                V5.validate_environment({**environment, **changes}, policies, self.expected)
        with self.assertRaises(V5.VerificationError):
            V5.validate_environment(environment, dict(total_count=1, branch_policies=[dict(name='*', type='tag')]), self.expected)

    def test_tag_immutability_no_exclusions_required(self):
        """Require strict repository immutability rather than mutable exceptions."""
        V5.validate_repository(self.repo, self.expected)
        for mode in ('MUTABLE', 'MUTABLE_WITH_EXCLUSION', 'IMMUTABLE_WITH_EXCLUSION'):
            with self.subTest(mode=mode), self.assertRaises(V5.VerificationError):
                V5.validate_repository({'repositories': [{**self.repo['repositories'][0], 'imageTagMutability': mode}]}, self.expected)

    def test_trust_pins_actual_immutable_oidc_subject_and_configuration(self):
        """Bind the trust draft to the observed immutable subject and reject drift."""
        settings = dict(use_default=True, use_immutable_subject=True, sub_claim_prefix=self.expected['oidc_sub_prefix'])
        V5.validate_oidc(settings, self.expected)
        for change in ({'use_immutable_subject': False}, {'sub_claim_prefix': 'repo:other'}, {'use_default': False}):
            with self.subTest(change=change), self.assertRaises(V5.VerificationError):
                V5.validate_oidc({**settings, **change}, self.expected)
        trust = json.loads((ROOT / 'deploy/iam/v5-artifact-oidc-trust.proposed.json').read_text())
        conditions = trust['Statement'][0]['Condition']['StringEquals']
        self.assertEqual(conditions['token.actions.githubusercontent.com:sub'],
                         self.expected['oidc_sub_prefix'] + ':environment:' + self.expected['environment'])

    def test_republication_even_same_image_is_rejected(self):
        """Reject existing tags even when they already identify the expected image."""
        V5.require_absent(self.absent, self.expected)
        for response in (self.published(), {}, {'failures': [{'failureCode': 'AccessDenied'}]}):
            with self.subTest(response=response), self.assertRaises(V5.VerificationError):
                V5.require_absent(response, self.expected)

    def test_registry_manifest_digest_and_config_id_are_distinct(self):
        """Verify the manifest hash independently of the Docker config image ID."""
        response = self.published()
        digest = V5.validate_manifest(response, self.expected)
        self.assertNotEqual(digest, self.expected['image_id'])
        response['images'][0]['imageId']['imageDigest'] = self.expected['image_id']
        with self.assertRaises(V5.VerificationError):
            V5.validate_manifest(response, self.expected)

    def test_wrong_registry_config_or_tag_rejected(self):
        """Reject a different registry target or config digest in the returned manifest."""
        for key, value in [('registryId', '000000000000'), ('repositoryName', 'other/repo')]:
            response = self.published()
            response['images'][0][key] = value
            with self.assertRaises(V5.VerificationError):
                V5.validate_manifest(response, self.expected)
        response = self.published()
        raw = json.loads(response['images'][0]['imageManifest'])
        raw['config']['digest'] = 'sha256:' + '0' * 64
        response['images'][0]['imageManifest'] = json.dumps(raw)
        response['images'][0]['imageId']['imageDigest'] = 'sha256:' + hashlib.sha256(json.dumps(raw).encode()).hexdigest()
        with self.assertRaises(V5.VerificationError):
            V5.validate_manifest(response, self.expected)

    def test_existing_tag_prevents_login_and_push(self):
        """Refuse login and Docker push when the release tag already exists."""
        with patch.object(V5, 'aws', side_effect=[json.dumps(self.repo), json.dumps(self.published())]), patch.object(V5, 'command') as command:
            with self.assertRaises(V5.VerificationError):
                V5.publish(self.expected)
            command.assert_not_called()

    def test_aws_query_error_is_not_treated_as_absent(self):
        """Treat failed ECR queries as errors rather than permission to publish."""
        with patch.object(V5, 'aws', side_effect=subprocess.CalledProcessError(1, ['aws'])), patch.object(V5, 'command') as command:
            with self.assertRaises(subprocess.CalledProcessError):
                V5.publish(self.expected)
            command.assert_not_called()

    def test_successful_publication_sequence_is_mocked_and_redacts_login(self):
        """Check the mocked push sequence and ensure login passwords are not printed."""
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {
            'RUNNER_TEMP': temporary, 'GITHUB_STEP_SUMMARY': str(Path(temporary) / 'summary')
        }), patch.object(V5, 'aws', side_effect=[json.dumps(self.repo), json.dumps(self.absent), json.dumps(self.published())]), \
                patch.object(V5, 'command', return_value=b'fake-password') as command, redirect_stdout(io.StringIO()) as output:
            V5.publish(self.expected)
            calls = command.call_args_list
            self.assertEqual(calls[1].kwargs['input'], b'fake-password')
            self.assertIn('push', calls[3].args[0])
            self.assertNotIn('fake-password', output.getvalue())
            record = json.loads((Path(temporary) / 'v5-publication.json').read_text())
            self.assertEqual(record['image_id'], self.expected['image_id'])
            self.assertNotEqual(record['registry_manifest_digest'], record['image_id'])

    def test_publisher_workflow_has_no_automatic_events_builds_or_deployment(self):
        """Keep the publication entry manual, protected and free of image build steps."""
        workflow = (ROOT / '.github/workflows/v5-artifact-ecr-publish.yml').read_text()
        self.assertNotIn('  pull_request:', workflow)
        self.assertNotIn('  push:', workflow)
        self.assertNotIn('build-push-action', workflow)
        self.assertIn('environment:\n      name: hobbytree-v5-ecr-publish', workflow)
        self.assertLess(workflow.index('Recheck approval configuration'), workflow.index('configure-aws-credentials@'))


if __name__ == '__main__':
    unittest.main()
