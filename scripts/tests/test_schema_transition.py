"""Failure-injection checks for the proposed schema transition; never contact AWS."""
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


T = load('schema_transition', 'scripts/deploy/schema-transition.py')
P = load('prepare_transition', 'scripts/ci/prepare-schema-transition.py')


class SchemaTransitionTests(unittest.TestCase):
    def plan(self):
        return dict(format=1, request='123-1', account='494915081306', region='ap-northeast-2',
                    instance=T.HOST.INSTANCE, rds='hobbytree-dev-private', revision='a' * 40,
                    candidate='sha256:' + 'a' * 64, previous='sha256:' + 'b' * 64,
                    before_schema='c' * 64, after_schema='d' * 64, host_helper_sha256='e' * 64,
                    test_report_sha256='f' * 64, expires_at=int(T.time.time()) + 3600,
                    db_host='hobbytree.example.ap-northeast-2.rds.amazonaws.com', app_user='hobbytree_app',
                    snapshot='hobbytree-v5-test', writers_fenced=True, baseline=1,
                    history=['1\tV1__init.sql\t1', '2\tV2__next.sql\t-2'])

    def engine(self):
        engine = T.Engine(self.plan(), Mock(), '/run/example', 'f' * 64)
        engine.record.update(stopped_at=datetime.now(timezone.utc).isoformat())
        engine.protected_inputs = Mock()
        engine.stopped = Mock()
        engine.journal = Mock()
        engine.migration = Mock()
        snapshot = dict(Status='available', SnapshotType='manual', DBInstanceIdentifier='hobbytree-dev-private',
                        DBSnapshotArn='arn:aws:rds:ap-northeast-2:494915081306:snapshot:hobbytree-v5-test',
                        SnapshotCreateTime=(datetime.now(timezone.utc) + timedelta(seconds=1)).isoformat())
        engine.aws = Mock(return_value={'DBSnapshots': [snapshot]})
        return engine, snapshot

    def test_plan_accepts_dynamic_contiguous_target(self):
        plan = self.plan()
        plan['history'].append('3\tV3__future.sql\t3')
        self.assertEqual(T.validate_plan(plan, '123-1')['baseline'], 1)

    def test_plan_rejects_expiry_wrong_account_replay_and_unfenced_writers(self):
        for key, value in [('expires_at', 1), ('account', '111111111111'), ('request', '124-1'), ('writers_fenced', False)]:
            with self.subTest(key=key):
                plan = self.plan(); plan[key] = value
                with self.assertRaises(T.HOST.ReleaseError): T.validate_plan(plan, '123-1')

    def test_missing_or_noncontiguous_history_cannot_be_approved(self):
        plan = self.plan(); plan['history'][1] = '19\tV19__future.sql\t0'
        with self.assertRaises(T.HOST.ReleaseError): T.validate_plan(plan, '123-1')

    def test_stale_snapshot_never_reaches_migration(self):
        engine, snapshot = self.engine()
        snapshot['SnapshotCreateTime'] = '2020-01-01T00:00:00+00:00'
        with self.assertRaises(T.HOST.ReleaseError): engine.migrate()
        engine.migration.assert_not_called()
        engine.journal.assert_not_called()

    def test_wrong_snapshot_instance_account_or_type_never_reaches_migration(self):
        for key, value in [('Status', 'creating'), ('SnapshotType', 'automated'),
                           ('DBInstanceIdentifier', 'other'), ('DBSnapshotArn', 'arn:aws:rds:ap-northeast-2:111111111111:snapshot:x')]:
            engine, snapshot = self.engine(); snapshot[key] = value
            with self.assertRaises(T.HOST.ReleaseError): engine.migrate()
            engine.migration.assert_not_called()

    def test_verified_schema_is_recorded_only_after_app_privileges_and_jpa(self):
        engine, _ = self.engine(); order = []
        engine.migration.side_effect = lambda mode: order.append(mode)
        engine.journal.side_effect = lambda state: order.append(state)
        engine.migrate()
        self.assertEqual(order, ['check-before', 'db-changing', 'migrate', 'check-after', 'validate-app', 'db-verified'])

    def test_partial_migration_or_jpa_failure_cannot_become_db_verified(self):
        for failed_mode in ('migrate', 'check-after', 'validate-app'):
            engine, _ = self.engine()
            def run(mode):
                if mode == failed_mode: raise T.HOST.ReleaseError('Injected failure')
            engine.migration.side_effect = run
            with self.assertRaises(T.HOST.ReleaseError): engine.migrate()
            self.assertNotIn(unittest.mock.call('db-verified'), engine.journal.call_args_list)
            # Neither starting a container nor any image rollback is part of the DB phase.
            engine.cmd.run.assert_not_called()

    def test_legacy_release_terminal_states_still_block_pending_transition(self):
        self.assertNotIn('quiesced', T.HOST.TERMINAL)
        self.assertNotIn('db-verified', T.HOST.TERMINAL)
        self.assertNotIn('recovery-required', T.HOST.TERMINAL)

    def test_inventory_checks_signed_crc_and_future_versions(self):
        items = P.inventory([('V2__next.sql', b'select 2;\n'), ('V1__init.sql', b'select 1;\r\n')])
        self.assertEqual([e['version'] for e in items], [1, 2])
        self.assertEqual(items[0]['checksum'], P.inventory([('V1__init.sql', b'select 1;\n')])[0]['checksum'])
        with self.assertRaises(ValueError): P.inventory([('V2__gap.sql', b'select 2;')])

    def test_proposed_document_binds_exact_scripts_and_separate_approvals(self):
        document = json.loads((ROOT / 'deploy/ssm/HobbyTree-Dev-SchemaTransition.proposed.json').read_text())
        commands = '\n'.join(document['mainSteps'][0]['inputs']['runCommand'])
        for name in ('ec2-release.py', 'schema-transition.py'):
            self.assertIn(hashlib.sha256((ROOT / 'scripts/deploy' / name).read_bytes()).hexdigest(), commands)
        self.assertEqual(len(document['mainSteps']), 1)
        for parameter in document['parameters'].values(): self.assertEqual(parameter['interpolationType'], 'ENV_VAR')
        self.assertIn('approve-backed-up-schema-change-no-image-rollback', document['parameters']['Approval']['allowedPattern'])


if __name__ == '__main__': unittest.main()
