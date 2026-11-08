"""Failure-injection checks for the proposed schema transition; never contact AWS."""
from datetime import datetime, timedelta, timezone
from contextlib import contextmanager, redirect_stderr
import io
import os
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
import subprocess
import sys
import tempfile
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
        return dict(format=3, request='123-1', account='494915081306', region='ap-northeast-2',
                    instance=T.HOST.INSTANCE, rds='hobbytree-dev-private', revision='a' * 40,
                    candidate='sha256:' + 'a' * 64, previous='sha256:' + 'b' * 64,
                    before_schema='c' * 64, after_schema='d' * 64, host_helper_sha256='e' * 64,
                    test_report_sha256='f' * 64, jar_sha256='0'*64, expires_at=int(T.time.time()) + 3600,
                    db_host='hobbytree.example.ap-northeast-2.rds.amazonaws.com', app_user='hobbytree_app', migration_user='hobbytree_owner',
                    database_owner='postgres', privileged_login_roles=['postgres'],
                    acl_control='external-operator', guard_policy='retire-after-snapshot',
                    snapshot='hobbytree-v5-test', writer_fence_policy='database-connect-acl', baseline=1,
                    history=['1\tV1__init.sql\t1', '2\tV2__next.sql\t-2'])

    def engine(self):
        engine = T.Engine(self.plan(), Mock(), '/run/example', 'f' * 64)
        engine.record.update(stopped_at=(datetime.now(timezone.utc)-timedelta(seconds=20)).isoformat(),
                             fence_verified_at=datetime.now(timezone.utc).isoformat(), fence_sha256='a'*64,
                             snapshot_arn='arn:aws:rds:ap-northeast-2:494915081306:snapshot:hobbytree-v5-test')
        engine.protected_inputs = Mock()
        engine.stopped = Mock()
        engine.journal = Mock()
        engine.migration = Mock(side_effect=lambda mode: 'a'*64 if mode == 'check-fence' else None)
        snapshot = dict(Status='available', SnapshotType='manual', DBInstanceIdentifier='hobbytree-dev-private',
                        DBSnapshotArn='arn:aws:rds:ap-northeast-2:494915081306:snapshot:hobbytree-v5-test',
                        SnapshotCreateTime=(datetime.now(timezone.utc) + timedelta(seconds=1)).isoformat())
        engine.aws = Mock(return_value={'DBSnapshots': [snapshot]})
        return engine, snapshot

    def test_plan_accepts_dynamic_contiguous_target(self):
        plan = self.plan()
        plan['history'].append('3\tV3__future.sql\t3')
        self.assertEqual(T.validate_plan(plan, '123-1')['baseline'], 1)

    def test_plan_rejects_expiry_wrong_account_replay_and_declared_fence(self):
        for key, value in [('expires_at', 1), ('expires_at', int(T.time.time())+60), ('account', '111111111111'), ('request', '124-1'), ('writers_fenced', True), ('format', 1)]:
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
        self.assertEqual(engine.migration.call_args_list, [unittest.mock.call('check-fence')])
        engine.journal.assert_not_called()

    def test_wrong_snapshot_instance_account_or_type_never_reaches_migration(self):
        for key, value in [('Status', 'creating'), ('SnapshotType', 'automated'),
                           ('DBInstanceIdentifier', 'other'), ('DBSnapshotArn', 'arn:aws:rds:ap-northeast-2:111111111111:snapshot:x')]:
            engine, snapshot = self.engine(); snapshot[key] = value
            with self.assertRaises(T.HOST.ReleaseError): engine.migrate()
            self.assertEqual(engine.migration.call_args_list, [unittest.mock.call('check-fence')])

    def test_verified_schema_is_recorded_only_after_app_privileges_and_jpa(self):
        engine, _ = self.engine(); order = []
        def run(mode):
            order.append(mode)
            return 'a'*64 if mode == 'check-fence' else None
        engine.migration.side_effect = run
        engine.journal.side_effect = lambda state: order.append(state)
        engine.migrate()
        self.assertEqual(order, ['check-fence', 'check-ready', 'check-fence', 'db-changing', 'migrate', 'check-after', 'validate-app', 'check-fence', 'db-verified'])

    def test_partial_migration_or_jpa_failure_cannot_become_db_verified(self):
        for failed_mode in ('migrate', 'check-after', 'validate-app'):
            engine, _ = self.engine()
            def run(mode):
                if mode == failed_mode: raise T.HOST.ReleaseError('Injected failure')
                return 'a'*64 if mode == 'check-fence' else None
            engine.migration.side_effect = run
            with self.assertRaises(T.HOST.ReleaseError): engine.migrate()
            self.assertNotIn(unittest.mock.call('db-verified'), engine.journal.call_args_list)
            # Neither starting a container nor any image rollback is part of the DB phase.
            engine.cmd.run.assert_not_called()

    def test_legacy_release_terminal_states_still_block_pending_transition(self):
        self.assertNotIn('quiesced', T.HOST.TERMINAL)
        self.assertNotIn('db-verified', T.HOST.TERMINAL)
        self.assertNotIn('recovery-required', T.HOST.TERMINAL)
        self.assertEqual(T.EXPECTED_STATE['migrate'], 'guard-retired')

    def test_plan_creation_records_policy_not_fence_fact(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory, 'report.json'); report.write_text('{}')
            args = SimpleNamespace(candidate='sha256:'+'a'*64, previous='sha256:'+'b'*64,
                                   request='123-1', test_report=report, db_host=self.plan()['db_host'],
                                   app_user='hobbytree_app', migration_user='hobbytree_owner', database_owner='postgres', privileged_login_role=['postgres'], snapshot='hobbytree-v5-test')
            prepared = P.plan(self.plan(), args)
            self.assertNotIn('writers_fenced', prepared)
            self.assertNotIn('fence_verified_at', prepared)
            self.assertEqual(T.validate_plan(prepared, '123-1')['writer_fence_policy'], 'database-connect-acl')

    def test_legacy_writers_fenced_switch_is_rejected_before_any_file_or_git_access(self):
        result = subprocess.run([sys.executable, str(ROOT/'scripts/ci/prepare-schema-transition.py'),
                                 '--revision', 'a'*40, '--baseline-sha', 'b'*40, '--history', 'missing',
                                 '--jar', 'missing', '--output', 'missing', '--writers-fenced'], capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn(b'unrecognized arguments: --writers-fenced', result.stderr)

    def test_quiesce_does_not_satisfy_migrate_phase_and_fence_failure_does_not_create_receipt(self):
        engine, _ = self.engine()
        engine.record = {'status':'quiesced'}
        engine.migration.side_effect = T.HOST.ReleaseError('Live session remains')
        with self.assertRaises(T.HOST.ReleaseError): engine.verify_writers()
        self.assertNotIn('fence_verified_at', engine.record)
        engine.journal.assert_not_called()
        self.assertNotEqual(T.EXPECTED_STATE['migrate'], 'quiesced')

    def test_changed_or_missing_fence_stops_before_snapshot_and_sql(self):
        for value in (None, 'b'*64):
            engine, _ = self.engine()
            engine.migration.side_effect = None; engine.migration.return_value = value
            with self.assertRaises(T.HOST.ReleaseError): engine.migrate()
            engine.aws.assert_not_called()
            engine.journal.assert_not_called()

    def test_snapshot_after_host_stop_but_before_database_fence_is_rejected(self):
        engine, snapshot = self.engine()
        snapshot['SnapshotCreateTime'] = (datetime.now(timezone.utc)-timedelta(seconds=10)).isoformat()
        with self.assertRaises(T.HOST.ReleaseError): engine.migrate()
        self.assertNotIn(unittest.mock.call('migrate'), engine.migration.call_args_list)

    def test_containment_attempts_every_action_and_closes_app_even_when_stop_fails(self):
        engine, _ = self.engine(); engine.record['status']='image-changing'
        engine.cmd.run.side_effect = [T.HOST.ReleaseError('stop failed'), b'', b'']
        engine.target = Mock()
        engine.recover()
        self.assertEqual(engine.cmd.run.call_count, 3)
        engine.target.assert_called_once_with('deregister-targets')
        engine.migration.assert_not_called()
        self.assertTrue(engine.record['external_operator_acl_recovery_required'])
        self.assertEqual(engine.record['containment_failures'], ['stop-backend'])
        engine.journal.assert_called_once_with('recovery-required')

    def test_same_sql_but_different_application_jar_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            engine, _ = self.engine()
            engine.release.temporary = Path(directory)
            Path(directory, 'candidate.jar').write_bytes(b'different application with same SQL')
            with self.assertRaises(T.HOST.ReleaseError):
                engine.prepare(engine.release, engine.plan['before_schema'], engine.plan['after_schema'])

    def test_phase_approval_cannot_be_reused_for_other_phase(self):
        for phase in T.APPROVALS:
            with patch.object(sys, 'argv', ['transition','--request','123-1','--plan-sha256','f'*64,
                                          '--phase',phase,'--approval','wrong-phase-approval','--approval-id','a'*32]), \
                 patch.object(T.HOST,'protected') as protected, redirect_stderr(io.StringIO()):
                self.assertEqual(T.main(),1)
                protected.assert_not_called()

    def test_recovery_still_holds_host_lock(self):
        held = {'value':False}
        @contextmanager
        def lock(*_):
            held['value']=True
            try: yield {}
            finally: held['value']=False
        with tempfile.TemporaryDirectory() as directory:
            plan=self.plan(); plan['host_helper_sha256']=hashlib.sha256((ROOT/'scripts/deploy/ec2-release.py').read_bytes()).hexdigest()
            path=Path(directory,'123-1.json'); path.write_text(json.dumps(plan))
            digest=hashlib.sha256(path.read_bytes()).hexdigest()
            engine=Mock(); engine.record={'status':'db-changing'}
            def failed_phase():
                engine.record['status']='db-changing'
                raise T.HOST.ReleaseError('injected')
            def contain(): self.assertTrue(held['value'])
            engine.migrate.side_effect=failed_phase; engine.recover.side_effect=contain
            with patch.object(sys,'argv',['transition','--request','123-1','--plan-sha256',digest,
                                         '--phase','migrate','--approval',T.APPROVALS['migrate'],'--approval-id','a'*32]), \
                 patch.object(T,'PLANS',Path(directory)), patch.object(T.HOST,'protected'), \
                 patch.object(T,'locked',lock), patch.object(T,'Engine',return_value=engine), \
                 patch.object(T,'authorize_phase',return_value='a'*64), patch.object(T,'consume_approval'), \
                 patch.object(T.tempfile,'TemporaryDirectory') as temporary, redirect_stderr(io.StringIO()):
                temporary.return_value.__enter__.return_value='/run/fixture'
                self.assertEqual(T.main(),1)
                engine.recover.assert_called_once()

    @unittest.skipUnless(os.name == 'posix', 'Linux openssl approval verification')
    def test_real_signature_context_expiry_and_tampering_are_enforced(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); key=root/'private.pem'; public=root/'public.pem'
            args=SimpleNamespace(approval_id='a'*32,request='123-1',phase='migrate',plan_sha256='f'*64)
            body=root/(args.approval_id+'.json'); signature=root/(args.approval_id+'.sig')
            subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(key)],check=True,capture_output=True)
            subprocess.run(['openssl','pkey','-in',str(key),'-pubout','-out',str(public)],check=True,capture_output=True)
            now=int(T.time.time())
            proof=dict(format=1,approval_id=args.approval_id,request=args.request,phase=args.phase,
                       plan_sha256=args.plan_sha256,issued_at=now-1,expires_at=now+3600)
            def sign(value):
                body.write_text(json.dumps(value))
                subprocess.run(['openssl','dgst','-sha256','-sign',str(key),'-out',str(signature),str(body)],check=True,capture_output=True)
            with patch.object(T,'PHASE_APPROVALS',root),patch.object(T,'APPROVAL_KEY',public),patch.object(T.HOST,'protected'):
                sign(proof)
                self.assertEqual(T.authorize_phase(args,T.HOST.Commands('/unused'),T.time.monotonic()+30), hashlib.sha256(body.read_bytes()).hexdigest())
                body.write_bytes(body.read_bytes()+b' ')
                with self.assertRaises(T.HOST.ReleaseError): T.authorize_phase(args,T.HOST.Commands('/unused'),T.time.monotonic()+30)
                for key_name,value in [('phase','deploy'),('request','124-1'),('plan_sha256','b'*64),
                                       ('issued_at',now+10),('expires_at',now+1),('extra','unreviewed')]:
                    changed=dict(proof);changed[key_name]=value;sign(changed)
                    with self.subTest(key=key_name), self.assertRaises(T.HOST.ReleaseError):
                        T.authorize_phase(args,T.HOST.Commands('/unused'),T.time.monotonic()+30)

    @unittest.skipUnless(os.name == 'posix', 'Linux durable one-time approval marker')
    def test_signed_approval_id_is_consumed_once_even_for_different_phase(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(T.HOST,'STATE',Path(directory)):
            args=SimpleNamespace(approval_id='a'*32,request='123-1',phase='migrate',plan_sha256='f'*64)
            T.consume_approval(args,'b'*64)
            marker=Path(directory,'schema-approval-'+args.approval_id+'.used')
            self.assertEqual(json.loads(marker.read_text())['approval_sha256'],'b'*64)
            args.phase='deploy'
            with self.assertRaises(FileExistsError): T.consume_approval(args,'b'*64)

    def test_guard_retirement_requires_new_snapshot_before_any_ddl(self):
        engine, snapshot = self.engine()
        snapshot['Status'] = 'creating'
        with self.assertRaises(T.HOST.ReleaseError): engine.retire_guard()
        engine.migration.assert_called_once_with('check-fence')
        engine.journal.assert_not_called()
        engine, _ = self.engine(); order=[]
        engine.migration.side_effect=lambda mode: (order.append(mode) or ('a'*64 if mode=='check-fence' else None))
        engine.journal.side_effect=lambda state: order.append(state)
        engine.retire_guard()
        self.assertEqual(order, ['check-fence','check-before','check-fence','guard-changing','retire-v45-guard','check-ready','check-fence','guard-retired'])
        self.assertEqual(T.EXPECTED_STATE['retire-guard'], 'writers-verified')
        self.assertEqual(T.EXPECTED_STATE['migrate'], 'guard-retired')

    def test_external_activation_never_grants_connect_and_requires_separate_phase(self):
        engine, _ = self.engine()
        engine.migration.side_effect=lambda mode: 'a'*64 if mode=='check-activation' else None
        engine.verify_activation()
        self.assertEqual(engine.migration.call_args_list, [unittest.mock.call('check-activation'),unittest.mock.call('check-active-after'),unittest.mock.call('validate-active-app')])
        engine.cmd.run.assert_not_called()
        self.assertEqual(T.EXPECTED_STATE['verify-activation'], 'db-verified')
        self.assertEqual(T.EXPECTED_STATE['deploy'], 'activation-verified')

    def test_unknown_database_owner_or_privileged_inventory_cannot_form_plan(self):
        for key, value in [('database_owner','hobbytree_owner'),('privileged_login_roles',[]),('acl_control','migration-account'),('guard_policy','pre-delete')]:
            plan=self.plan(); plan[key]=value
            with self.assertRaises(T.HOST.ReleaseError): T.validate_plan(plan,'123-1')

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
        self.assertGreaterEqual(int(document['mainSteps'][0]['inputs']['timeoutSeconds']), T.HOST.FORWARD_SECONDS+1100)
        for parameter in document['parameters'].values(): self.assertEqual(parameter['interpolationType'], 'ENV_VAR')
        self.assertIn('approve-backed-up-schema-change-no-image-rollback', document['parameters']['Approval']['allowedPattern'])


if __name__ == '__main__': unittest.main()
