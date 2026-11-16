"""AWS-free adversarial checks of the new main-only deployment boundary."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


H = load('host', 'scripts/deploy/main_release_host.py')
C = load('ci_main', 'scripts/ci/main-release.py')
T = load('transition_checks', 'scripts/ci/verify-v24-transition.py')


class MainReleaseTests(unittest.TestCase):
    def setUp(self):
        self.request = dict(format=1, revision='a'*40, digest='sha256:'+'b'*64, image_id='sha256:'+'c'*64,
                            sql_set_sha256='d'*64, target=26, request='123-1', expires_at=1600)
        self.active = dict(status='complete', digest='sha256:'+'e'*64, migrations=[{}]*24)

    def test_payload_rejects_tags_shell_unknown_fields_boolean_versions_and_expiry(self):
        H.payload(self.request, 1000)
        for changes in ({'digest': 'latest'}, {'request': '1;id'}, {'target': True}, {'target': 23},
                        {'expires_at': 999}, {'expires_at': 999999}, {'script': '/bin/sh'}, {'revision': 'main'}):
            with self.subTest(changes=changes), self.assertRaises((ValueError, TypeError)):
                H.payload({**self.request, **changes}, 1000)

    def test_new_versions_execute_backup_before_migration_and_publish_last(self):
        host, record = Mock(), Mock()
        self.assertEqual(H.execute(host, self.request, self.active, record), 'complete')
        self.assertEqual([x.args[0] for x in host.perform.call_args_list],
                         ['drain', 'stop', 'fence', 'backup', 'migrate', 'validate', 'unfence', 'start', 'verify', 'publish'])
        host.restore.assert_not_called()

    def test_same_schema_does_not_write_migrations_or_create_snapshot(self):
        host = Mock()
        H.execute(host, {**self.request, 'target': 24}, self.active, Mock())
        self.assertNotIn('migrate', [x.args[0] for x in host.perform.call_args_list])
        self.assertNotIn('backup', [x.args[0] for x in host.perform.call_args_list])

    def test_preflight_failure_does_not_stop_running_application(self):
        host = Mock()
        host.preflight.side_effect = ValueError('drift')
        self.assertEqual(H.execute(host, self.request, self.active, Mock()), 'failed')
        host.perform.assert_not_called()
        host.contain.assert_not_called()
        host.restore.assert_not_called()

    def test_backup_failure_can_restore_before_any_migration(self):
        host = Mock()
        host.perform.side_effect = lambda phase, *_: (_ for _ in ()).throw(ValueError()) if phase == 'backup' else None
        self.assertEqual(H.execute(host, self.request, self.active, Mock()), 'restored')
        host.restore.assert_called_once_with(self.active, True)

    def test_migration_or_later_failure_never_automatically_downgrades(self):
        for phase in ('migrate', 'validate', 'unfence', 'start', 'verify', 'publish'):
            with self.subTest(phase=phase):
                host = Mock()
                host.perform.side_effect = lambda current, *_: (_ for _ in ()).throw(ValueError()) if current == phase else None
                self.assertEqual(H.execute(host, self.request, self.active, Mock()), 'failed')
                host.restore.assert_not_called()
                host.contain.assert_called_once()

    def test_same_schema_candidate_failure_restores_previous_image(self):
        host = Mock()
        host.perform.side_effect = lambda phase, *_: (_ for _ in ()).throw(ValueError()) if phase == 'publish' else None
        self.assertEqual(H.execute(host, {**self.request, 'target': 24}, self.active, Mock()), 'restored')

    def test_unresolved_release_is_not_retried(self):
        host = Mock()
        with self.assertRaises(ValueError):
            H.execute(host, self.request, {**self.active, 'status': 'blocked'}, Mock())
        host.preflight.assert_not_called()

    def test_absent_backend_after_failed_create_still_restores_same_schema(self):
        with tempfile.TemporaryDirectory() as temp:
            host = H.Host({}, Path(temp))
            host.previous = {'Id': 'e'*64}
            host.command = Mock(return_value=b'')  # No backend container exists.
            host.target, host.wait_target = Mock(), Mock()
            host.preflight, host.restore = Mock(), Mock()
            host.perform = Mock(side_effect=lambda phase, *_: (_ for _ in ()).throw(ValueError())
                                if phase == 'start' else None)
            self.assertEqual(H.execute(host, {**self.request, 'target': 24}, self.active, Mock()), 'restored')
            host.target.assert_called_once_with('deregister-targets')
            host.wait_target.assert_called_once_with('unused')
            host.restore.assert_called_once_with(self.active, True)
            self.assertFalse(any('stop' in call.args[0] for call in host.command.call_args_list))

    def test_restore_public_validation_failure_recontains_old_image(self):
        host, record = Mock(), Mock()
        request = {**self.request, 'target': 24}
        host.perform.side_effect = lambda phase, *_: (_ for _ in ()).throw(ValueError()) if phase == 'publish' else None
        host.restore.side_effect = ValueError('old image public probe failed after ALB register')
        self.assertEqual(H.execute(host, request, self.active, record), 'failed')
        self.assertEqual(host.contain.call_count, 2)
        host.contain.assert_called_with(request, self.active, restoring=True)
        record.assert_any_call('rollback', 'failed')
        record.assert_any_call('rollback-containment', 'done')

    def test_restore_containment_accepts_only_owned_previous_image(self):
        with tempfile.TemporaryDirectory() as temp:
            host = H.Host({}, Path(temp))
            host.previous = {'Id': 'a'*64}
            active = {**self.active, 'image_id': 'sha256:'+'e'*64}
            restored = {'Id': 'f'*64, 'Image': active['image_id'], 'Config': {
                'Labels': {'hobbytree.main-release': self.request['request']}}}
            host.container = Mock(return_value=restored)
            events = []
            host.target = Mock(side_effect=lambda *_: events.append('deregister'))
            host.wait_target = Mock(side_effect=lambda *_: events.append('isolated'))
            host.command = Mock(side_effect=lambda *_: events.append('stop'))
            host.contain(self.request, active, restoring=True)
            self.assertEqual(events, ['deregister', 'isolated', 'stop'])
            host.command.assert_called_once_with(['docker', 'stop', '--time', '40', restored['Id']], 60)
            for foreign in ({**restored, 'Image': self.request['image_id']}, {**restored, 'Config': {'Labels': {}}}):
                host.container.return_value = foreign
                host.command.reset_mock()
                with self.assertRaises(ValueError):
                    host.contain(self.request, active, restoring=True)
                host.command.assert_not_called()

    def test_absence_is_optional_but_multiple_or_foreign_containers_block_restore(self):
        with tempfile.TemporaryDirectory() as temp:
            host = H.Host({}, Path(temp))
            host.command = Mock(return_value=b'')
            with self.assertRaises(ValueError):
                host.container('backend')
            self.assertIsNone(host.container('backend', allow_absent=True))
            host.command.return_value = ('a'*64 + '\n' + 'b'*64 + '\n').encode()
            with self.assertRaises(ValueError):
                host.container('backend', allow_absent=True)
            host.previous = {'Id': 'e'*64}
            host.target, host.wait_target, host.restore = Mock(), Mock(), Mock()
            host.container = Mock(return_value={'Id': 'f'*64, 'Image': 'foreign', 'Config': {}})
            with self.assertRaises(ValueError):
                host.contain(self.request, self.active)
            host.restore.assert_not_called()

    def test_journal_is_written_before_side_effect_and_failure_is_preserved(self):
        operations = []
        host = Mock()
        host.perform.side_effect = lambda phase, *_: operations.append(('effect', phase))
        H.execute(host, self.request, self.active, lambda phase, status: operations.append((status, phase)))
        for i, event in enumerate(operations):
            if event[0] == 'effect':
                self.assertEqual(operations[i-1], ('running', event[1]))

    def test_packaged_v25_v26_history_and_fingerprint_match_existing_parser(self):
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / 'app.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                for i in reversed(range(1, 27)):
                    archive.writestr(f'BOOT-INF/classes/db/migration/V{i}__fixture.sql', f'SELECT {i};\r\n')
            rows = H.jar_history(jar)
            self.assertEqual(len(rows), 26)
            self.assertEqual(rows[-1]['name'], 'V26__fixture.sql')
            self.assertEqual(H.fingerprint(rows), C.RELEASE.PUBLISH.SCHEMA.schema_fingerprint(jar))

    def test_duplicate_scripts_and_nontransactional_sidecars_rejected(self):
        for names in (['V1__a.sql', 'V1__b.sql'], ['V1__a.sql', 'V1__a.sql.conf'], ['V2__a.sql']):
            with tempfile.TemporaryDirectory() as temp:
                jar = Path(temp) / 'app.jar'
                with zipfile.ZipFile(jar, 'w') as archive:
                    for name in names:
                        archive.writestr('BOOT-INF/classes/db/migration/' + name, 'SELECT 1;')
                with self.assertRaises(ValueError):
                    H.jar_history(jar)

    def test_environment_requires_main_and_named_operator(self):
        env = {'name': C.ENVIRONMENT, 'can_admins_bypass': False,
               'deployment_branch_policy': {'protected_branches': False, 'custom_branch_policies': True},
               'protection_rules': [{'type': 'required_reviewers', 'prevent_self_review': False,
                   'reviewers': [{'type': 'User', 'reviewer': {'login': 'rladnqls14'}}]}]}
        branches = {'total_count': 1, 'branch_policies': [{'name': 'main', 'type': 'branch'}]}
        C.protection(env, branches)
        for bad in ({**env, 'protection_rules': []}, {**env, 'can_admins_bypass': True}):
            with self.assertRaises(Exception):
                C.protection(bad, branches)
        with self.assertRaises(Exception):
            C.protection(env, {'total_count': 1, 'branch_policies': [{'name': 'develop', 'type': 'branch'}]})

    def test_wrong_branch_event_and_checkout_rejected(self):
        base = dict(GITHUB_SHA='a'*40, BASELINE_SHA='b'*40, RELEASE_SHA='a'*40,
                    GITHUB_EVENT_NAME='push', GITHUB_REPOSITORY=C.RELEASE.REPOSITORY, GITHUB_REF='refs/heads/main')
        for changes in ({'GITHUB_REF': 'refs/heads/develop'}, {'GITHUB_EVENT_NAME': 'pull_request'},
                        {'GITHUB_REPOSITORY': 'other/fork'}, {'RELEASE_SHA': 'c'*40}):
            with patch.dict(os.environ, {**base, **changes}), self.assertRaises(Exception):
                C.RELEASE.context()

    def test_ssm_document_does_not_interpolate_payload_as_shell_code(self):
        doc = json.loads((ROOT / 'deploy/main-release/HobbyTree-Main-Release.json').read_text())
        self.assertEqual(doc['parameters']['ReleasePayload']['interpolationType'], 'ENV_VAR')
        commands = '\n'.join(doc['mainSteps'][0]['inputs']['runCommand'])
        self.assertIn('"$SSM_ReleasePayload"', commands)
        self.assertNotIn('{{', commands)

    def test_main_provenance_rejects_squash_foreign_head_and_missing_checks(self):
        sha, head = 'a'*40, 'b'*40
        commit = {'parents': [{'sha': 'c'*40}, {'sha': head}]}
        pr = {'merged_at': '2026-11-15', 'merge_commit_sha': sha, 'base': {'ref': 'main'},
              'head': {'ref': 'develop', 'sha': head, 'repo': {'full_name': C.RELEASE.REPOSITORY}}}
        checks = {'check_runs': [{'name': name, 'head_sha': head, 'conclusion': 'success',
                                 'app': {'slug': 'github-actions'}} for name in
                    ('Spotless Check', 'Docker amd64 and PostgreSQL 17', 'Flyway PostgreSQL 17 migrations')]}
        with patch.object(C.RELEASE.PUBLISH, 'api_json', side_effect=[commit, [pr], checks]):
            self.assertEqual(C.provenance(sha), head)
        for responses in ([{'parents': [{'sha': head}]}], [commit, []], [commit, [pr], {'check_runs': []}],
                          [commit, [{**pr, 'head': {**pr['head'], 'ref': 'other'}}]]):
            with self.subTest(responses=responses), patch.object(C.RELEASE.PUBLISH, 'api_json', side_effect=responses), self.assertRaises(Exception):
                C.provenance(sha)

    def test_unfilled_host_example_cannot_be_used_for_deployment(self):
        example = json.loads((ROOT / 'deploy/main-release/config.example.json').read_text())
        with self.assertRaises(ValueError):
            H.configuration(example)
        with self.assertRaises(ValueError):
            H.configuration({**example, 'enabled': True})

    def test_future_schema_runs_generic_suite_without_retired_v24_contract(self):
        with tempfile.TemporaryDirectory() as temp:
            for i in range(1, 25):
                (Path(temp) / f'V{i}__fixture.sql').touch()
            self.assertEqual(T.suites_for(temp), [T.LEGACY, T.GENERIC])
            (Path(temp) / 'V25__real_next_migration.sql').touch()
            self.assertEqual(T.suites_for(temp), [T.GENERIC])
            (Path(temp) / 'V27__missing_predecessor.sql').touch()
            with self.assertRaises(ValueError):
                T.suites_for(temp)


if __name__ == '__main__':
    unittest.main()
