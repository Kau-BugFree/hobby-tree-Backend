"""AWS-free adversarial checks of the new main-only deployment boundary."""
import copy
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

    def test_preflight_and_failed_record_errors_do_not_touch_running_application(self):
        host = Mock()
        host.preflight.side_effect = ValueError('drift')
        record = Mock(side_effect=OSError('evidence disk unavailable'))
        self.assertEqual(H.execute(host, self.request, self.active, record), 'failed')
        host.perform.assert_not_called()
        host.contain.assert_not_called()
        host.restore.assert_not_called()

    def test_persistent_journal_failure_after_publish_still_contains_candidate(self):
        for target in (24, 26):
            with self.subTest(target=target):
                host = Mock()
                events = []
                def record(phase, status):
                    events.append((phase, status))
                    if ('publish', 'done') in events:
                        raise OSError('evidence disk full')
                request = {**self.request, 'target': target}
                self.assertEqual(H.execute(host, request, self.active, record), 'failed')
                host.perform.assert_any_call('publish', request, self.active)
                if target == 24:
                    # Restore success cannot be committed: isolate the restored image too.
                    host.restore.assert_called_once_with(self.active, True)
                    self.assertEqual(host.contain.call_count, 2)
                    host.contain.assert_called_with(request, self.active, restoring=True)
                else:
                    host.contain.assert_called_once_with(request, self.active)
                    host.restore.assert_not_called()

    def test_failed_journal_does_not_prevent_safe_pre_migration_restore(self):
        host = Mock()
        host.perform.side_effect = lambda phase, *_: (_ for _ in ()).throw(ValueError()) if phase == 'backup' else None
        def record(phase, status):
            if status == 'failed':
                raise OSError('failure record unavailable')
        self.assertEqual(H.execute(host, self.request, self.active, record), 'restored')
        host.contain.assert_called_once_with(self.request, self.active)
        host.restore.assert_called_once_with(self.active, True)

    def test_restore_failure_and_journal_errors_still_recontain_old_image(self):
        host = Mock()
        request = {**self.request, 'target': 24}
        host.perform.side_effect = lambda phase, *_: (_ for _ in ()).throw(ValueError()) if phase == 'publish' else None
        host.restore.side_effect = ValueError('restored image public probe failed')
        def record(phase, status):
            if status == 'failed' or phase == 'rollback-containment':
                raise OSError('evidence disk unavailable')
        self.assertEqual(H.execute(host, request, self.active, record), 'failed')
        self.assertEqual(host.contain.call_count, 2)
        host.contain.assert_called_with(request, self.active, restoring=True)

    def test_containment_failure_with_journal_error_never_restores(self):
        host = Mock()
        host.perform.side_effect = ValueError('drain failed')
        host.contain.side_effect = ValueError('isolation unavailable')
        def record(phase, status):
            if status == 'failed':
                raise OSError('evidence disk unavailable')
        self.assertEqual(H.execute(host, self.request, self.active, record), 'failed')
        host.contain.assert_called_once_with(self.request, self.active)
        host.restore.assert_not_called()

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

    def containment_host(self):
        host = H.Host({}, Path('/unused-test-directory'))
        host.previous = {'Id': 'a'*64}
        host.stop_database, host.target, host.wait_target, host.command = Mock(), Mock(), Mock(), Mock()
        host.container = Mock(return_value={'Id': 'b'*64, 'Image': self.request['image_id'],
            'Config': {'Labels': {'hobbytree.main-release': self.request['request']}}})
        return host

    def test_each_isolation_failure_still_attempts_remaining_operations(self):
        for failed in ('database', 'deregister', 'wait', 'backend'):
            with self.subTest(failed=failed):
                host = self.containment_host()
                events = []
                def operation(name):
                    def run(*_):
                        events.append(name)
                        if name == failed:
                            raise ValueError('isolation unavailable')
                    return run
                host.stop_database.side_effect = operation('database')
                host.target.side_effect = operation('deregister')
                host.wait_target.side_effect = operation('wait')
                host.command.side_effect = operation('backend')
                with self.assertRaises(ValueError):
                    host.contain(self.request, self.active)
                self.assertEqual(events, ['database', 'deregister'] + ([] if failed == 'deregister' else ['wait']) + ['backend'])
                host.container.assert_called_once_with('backend', allow_absent=True)

    def test_simultaneous_isolation_failures_attempt_all_three_groups(self):
        host = self.containment_host()
        host.stop_database.side_effect = ValueError('database stop failed')
        host.target.side_effect = ValueError('ALB unavailable')
        host.command.side_effect = ValueError('backend stop failed')
        with self.assertRaises(ValueError):
            host.contain(self.request, self.active)
        host.stop_database.assert_called_once()
        host.target.assert_called_once_with('deregister-targets')
        host.command.assert_called_once_with(['docker', 'stop', '--time', '40', 'b'*64], 60)

    def test_absent_backend_does_not_hide_failed_alb_isolation(self):
        host = self.containment_host()
        host.target.side_effect = ValueError('ALB unavailable')
        host.container.return_value = None
        with self.assertRaises(ValueError):
            host.contain(self.request, self.active)
        host.container.assert_called_once_with('backend', allow_absent=True)
        host.command.assert_not_called()

    def test_failed_alb_isolation_never_allows_stopping_foreign_backend(self):
        host = self.containment_host()
        host.target.side_effect = ValueError('ALB unavailable')
        host.container.return_value = {'Id': 'c'*64, 'Image': 'foreign', 'Config': {'Labels': {}}}
        with self.assertRaises(ValueError):
            host.contain(self.request, self.active)
        host.container.assert_called_once_with('backend', allow_absent=True)
        host.command.assert_not_called()

    def test_database_and_alb_timeouts_leave_backend_cleanup_budget(self):
        host = self.containment_host()
        clock = [1000.0]
        budgets = []
        def exhaust(*_):
            budgets.append(host.deadline - clock[0])
            clock[0] = host.deadline + 1
            raise TimeoutError('cleanup timeout')
        host.stop_database.side_effect = exhaust
        host.target.side_effect = exhaust
        def stop(*_):
            budgets.append(host.deadline - clock[0])
            self.assertGreater(host.deadline, clock[0])
        host.command.side_effect = stop
        with patch.object(H.time, 'monotonic', side_effect=lambda: clock[0]), self.assertRaises(ValueError):
            host.contain(self.request, self.active)
        self.assertEqual(budgets, [90, 420, 88])
        self.assertEqual(host.deadline, 1600)
        host.command.assert_called_once()

    def test_partial_containment_stops_backend_but_execute_does_not_restore(self):
        host = self.containment_host()
        host.preflight = Mock()
        host.perform = Mock(side_effect=lambda phase, *_: (_ for _ in ()).throw(ValueError()) if phase == 'publish' else None)
        host.target.side_effect = ValueError('ALB unavailable')
        host.restore = Mock()
        self.assertEqual(H.execute(host, {**self.request, 'target': 24}, self.active, Mock()), 'failed')
        host.command.assert_called_once_with(['docker', 'stop', '--time', '40', 'b'*64], 60)
        host.restore.assert_not_called()

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

    def frozen_responses(self):
        sha, head = 'a'*40, 'b'*40
        repo = {'full_name': C.RELEASE.REPOSITORY}
        commit = {'parents': [{'sha': 'c'*40}, {'sha': head}]}
        main_pr = {'merged_at': 'fixture', 'merge_commit_sha': sha, 'base': {'ref': 'main', 'repo': repo},
                   'head': {'ref': 'deploy/202-v25-reconciliation-release', 'sha': head, 'repo': repo}}
        source_pr = {'merged_at': 'fixture', 'merge_commit_sha': 'd'*40,
                     'base': {'ref': 'develop', 'repo': repo},
                     'head': {'ref': 'fix/214-frozen-release-provenance', 'sha': head, 'repo': repo}}
        lineage = {'status': 'ahead', 'base_commit': {'sha': head}, 'merge_base_commit': {'sha': head}}
        checks = {'check_runs': [{'name': name, 'head_sha': head, 'conclusion': 'success',
                                 'app': {'slug': 'github-actions'}} for name in
                    ('Spotless Check', 'Docker amd64 and PostgreSQL 17', 'Flyway PostgreSQL 17 migrations')]}
        return [commit, [main_pr], [source_pr], lineage, checks]

    def test_frozen_merged_develop_head_or_merge_with_exact_checks_passes(self):
        for merge_source in (False, True):
            responses = self.frozen_responses()
            if merge_source:
                responses[2][0]['merge_commit_sha'] = 'b'*40
                responses[2][0]['head']['sha'] = 'd'*40
            with self.subTest(merge_source=merge_source), patch.object(C.RELEASE.PUBLISH, 'api_json', side_effect=responses) as api:
                self.assertEqual(C.provenance('a'*40), 'b'*40)
                self.assertEqual(api.call_args_list[3].args[0], 'compare/' + 'b'*40 + '...develop')

    def test_frozen_unmerged_foreign_extra_commit_and_wrong_source_refuse(self):
        changes = [lambda r: r[2][0].update(merged_at=None),
                   lambda r: r[2][0]['head'].update(sha='e'*40),
                   lambda r: r[2][0]['head'].update(repo={'full_name': 'foreign/repo'}),
                   lambda r: r[2][0]['base'].update(repo={'full_name': 'foreign/repo'}),
                   lambda r: r[2][0]['base'].update(ref='main'),
                   lambda r: r[2].clear(),
                   lambda r: r[2].append(r[2][0])]
        for change in changes:
            responses = self.frozen_responses()
            change(responses)
            with self.subTest(change=change), patch.object(C.RELEASE.PUBLISH, 'api_json', side_effect=responses), self.assertRaises(Exception):
                C.provenance('a'*40)

    def test_frozen_divergent_or_missing_lineage_refuses(self):
        for lineage in ({}, {'status': 'behind'}, {'status': 'diverged'},
                        {'status': 'ahead', 'base_commit': {'sha': 'b'*40}, 'merge_base_commit': {'sha': 'e'*40}},
                        {'status': 'ahead', 'base_commit': {'sha': 'e'*40}, 'merge_base_commit': {'sha': 'b'*40}}):
            responses = self.frozen_responses()
            responses[3] = lineage
            with self.subTest(lineage=lineage), patch.object(C.RELEASE.PUBLISH, 'api_json', side_effect=responses), self.assertRaises(Exception):
                C.provenance('a'*40)

    def test_frozen_missing_failed_stale_or_forged_checks_refuse(self):
        for change in (lambda c: c.pop(), lambda c: c[0].update(conclusion='failure'),
                       lambda c: c[0].update(head_sha='e'*40), lambda c: c[0].update(app={'slug': 'other'})):
            responses = self.frozen_responses()
            change(responses[4]['check_runs'])
            with self.subTest(change=change), patch.object(C.RELEASE.PUBLISH, 'api_json', side_effect=responses), self.assertRaises(Exception):
                C.provenance('a'*40)

    def test_frozen_name_foreign_main_parent_and_squash_still_refuse(self):
        changes = [lambda r: r[1][0]['head'].update(ref='release/arbitrary'),
                   lambda r: r[1][0]['head'].update(ref='deploy/0-invalid'),
                   lambda r: r[1][0]['head'].update(repo={'full_name': 'foreign/repo'}),
                   lambda r: r[1][0]['head'].update(sha='e'*40),
                   lambda r: r[0]['parents'].pop()]
        for change in changes:
            responses = self.frozen_responses()
            change(responses)
            with self.subTest(change=change), patch.object(C.RELEASE.PUBLISH, 'api_json', side_effect=responses), self.assertRaises(Exception):
                C.provenance('a'*40)

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


class RedisMountVerificationTests(unittest.TestCase):
    def setUp(self):
        self.mounts = [
            {'Type': 'bind', 'Source': '/run/fixture', 'Destination': '/fixture', 'RW': False,
             'Mode': 'ro', 'Propagation': 'rprivate'},
            {'Type': 'volume', 'Name': 'data', 'Source': '/volume/data', 'Destination': '/data',
             'Driver': 'local', 'RW': True, 'Mode': 'rw'}]
        self.host = H.Host({}, Path('/unused'))
        self.host.redis = {'Id': 'redis-id', 'Mounts': copy.deepcopy(self.mounts)}
        self.backend = {'Image': 'new-image', 'State': {'Health': {'Status': 'healthy'}}}
        self.host.probes = Mock()

    def verify(self, mounts, redis_id='redis-id'):
        self.host.container = Mock(side_effect=[self.backend, {'Id': redis_id, 'Mounts': mounts}])
        self.host.perform('verify', {'image_id': 'new-image'}, {})

    def test_real_verify_accepts_redis_mount_reordering_and_uses_one_snapshot(self):
        before = copy.deepcopy(self.host.redis)
        self.verify(list(reversed(self.mounts)))
        self.host.probes.assert_called_once_with('http://127.0.0.1:8080')
        self.assertEqual([call.args for call in self.host.container.call_args_list], [('backend',), ('redis',)])
        self.assertEqual(self.host.redis, before)

    def test_real_verify_rejects_changed_fields_and_mount_multiplicity(self):
        for change in ({'Source': '/other'}, {'Destination': '/other'}, {'RW': True}, {'Mode': 'rw'},
                       {'Propagation': 'shared'}, {'Type': 'volume'}, {'NewField': 'value'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.verify([{**self.mounts[0], **change}, self.mounts[1]])
        for mounts in ([], self.mounts[:1], self.mounts + self.mounts[:1], {}, ['invalid']):
            with self.subTest(mounts=mounts), self.assertRaises(ValueError):
                self.verify(mounts)
        with self.assertRaises(ValueError):
            self.verify(self.mounts, redis_id='other')
        self.host.probes.assert_not_called()


if __name__ == '__main__':
    unittest.main()
