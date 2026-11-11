"""Isolated journal and approval tests for recovery after V24 configuration."""
import copy
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import test_v24_backup_recovery as backup_tests
from test_v24_transition import H, P, T, module

B = backup_tests.R
C = module('v24_recover_configure')


@unittest.skipUnless(os.name == 'posix' and os.geteuid() == 0, 'isolated Linux root fixtures only')
class ConfigureRecoveryTests(unittest.TestCase):
    def setUp(self):
        """Extend the historical backup fixture with a failed configure request."""
        backup_tests.BackupRecoveryTests.setUp(self)
        prior = dict(self.proof, recovery_sha256=C.BACKUP_TOOL_SHA)
        with patch.object(B, 'observe', return_value='fixture-stop'):
            B.reconcile(P, H, T, self.host, self.journal, prior, P.canonical(prior), b'fixture', self.new_raw)
        self.verify_signature.reset_mock()
        self.source_journal = self.journal
        self.source_plan = self.new
        self.source_state = self.journal.load()
        self.source_state.update(phase='failed', failed_phase='configure',
                                 containment=dict.fromkeys(('database', 'exclude', 'disable', 'stop'), True))
        self.source_state['facts'].update(
            schema_version=24, fence_sha256='5'*64, fence_verified_at='fixture-verified',
            snapshot_arn=f"arn:aws:rds:{self.new['region']}:{self.new['account']}:snapshot:{self.new['snapshot']}",
            database_cleanup=[dict(mode='check-after', stopped_or_absent=True)])
        for nonce, phase in zip(('a'*32, 'b'*32, 'c'*32, 'd'*32), C.SOURCE_PHASES):
            proof = dict(format=1, action='hobbytree-v18-v24', request=self.new['request'],
                         phase=phase, plan_sha256=self.journal.plan_hash, approval_id=nonce,
                         issued_at=900, expires_at=3000)
            self.journal.authorize_once(dict(phase=P.PHASES[phase][0], approvals={}),
                                        proof, P.canonical(proof), b'fixture')
            self.source_state['approvals'][phase] = nonce
        self.journal.save(self.source_state)
        self.new = copy.deepcopy(self.source_plan)
        self.new.update(request='0'*32, snapshot='hobbytree-v24-'+'0'*32)
        self.repaired = b'fixture repaired host'
        self.new['bundle']['v24_host.py'] = P.sha(self.repaired)
        self.new_raw = P.canonical(self.new)
        self.journal = P.Journal(self.root, self.new['request'], P.sha(self.new_raw))
        self.proof = dict(prior, action=C.ACTION, request=self.new['request'], plan_sha256=self.journal.plan_hash,
                          source_request=self.source_plan['request'], source_plan_sha256=self.source_journal.plan_hash,
                          source_state_sha256=P.sha(P.canonical(self.source_state)), approval_id='6'*32)
        self.host.plan, self.host.journal = self.new, self.journal
        self.host.bundle = self.root/'bundle'
        self.host.bundle.mkdir(mode=0o700)
        P.create(self.host.bundle/'v24_host.py', self.repaired)
        self.install = self.root/'install'
        self.install.mkdir(mode=0o700)
        P.create(self.install/'v24_host.py', b'old host')
        self.pointer = self.root/'pointer'
        P.create(self.pointer, (self.source_plan['request']+'\n').encode())
        patcher = patch.multiple(H, INSTALL=self.install, POINTER=self.pointer)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.host.database.reset_mock()

    def recover(self):
        """Use the real journal and signatures ledger with simulated host observations."""
        with patch.object(C, 'observe', return_value=(self.settings, self.legacy, b'candidate settings')):
            return C.reconcile(B, P, H, T, self.host, self.journal, self.proof,
                               P.canonical(self.proof), b'fresh signature', self.new_raw)

    def test_recovery_preserves_all_prior_requests_and_reuses_verified_v24(self):
        """Publish a configured request with the original backup and no repeated DB work."""
        history = {file: file.read_bytes() for file in self.root.glob('v24-*/*') if file.is_file()}
        self.assertEqual(self.recover()['phase'], 'configured')
        self.assertEqual(history, {file: file.read_bytes() for file in history})
        state = self.journal.load()
        self.assertEqual(state['facts']['snapshot_arn'], self.source_state['facts']['snapshot_arn'])
        self.assertEqual(state['facts']['candidate_settings_sha256'], P.sha(b'candidate settings'))
        self.assertEqual(state['approvals'], {C.PHASE: self.proof['approval_id']})
        self.assertEqual(P.read(self.pointer).decode().strip(), self.new['request'])
        self.assertEqual(P.read(self.install/'v24_host.py'), self.repaired)
        self.assertEqual([call.args[0] for call in self.host.database.call_args_list], ['check-after', 'validate-app'])
        self.assertEqual(self.host.check_fence.call_count, 2)
        self.assertEqual(self.verify_signature.call_count, 10)
        self.host.perform.assert_not_called()
        self.host.target.assert_not_called()
        for phase in ('migrate', 'configure'):
            with self.assertRaises(ValueError):
                self.journal.authorize_once(state, {'phase': phase}, b'', b'')

    def test_rejects_artifact_changes_beyond_the_repaired_validator(self):
        """A configure recovery cannot also change the catalogue."""
        self.new['bundle']['catalogue.sql'] = P.sha(b'different catalogue')
        with self.assertRaises(ValueError):
            self.recover()
        self.assertFalse(self.journal.directory.exists())

    def test_missing_historical_nonce_blocks_before_db_checks(self):
        """A historical phase requires its original protected consumption marker."""
        nonce = self.source_state['approvals']['migrate']
        (self.root/('v24-approval-'+nonce+'.used')).unlink()
        with self.assertRaises(OSError):
            self.recover()
        self.host.database.assert_not_called()
        self.assertFalse(self.journal.directory.exists())

    def test_fence_or_schema_failure_leaves_source_gate_and_installation(self):
        """Record failed verification and the confirmed result of DB cleanup."""
        gate = P.read(self.root/'active.json')
        self.host.database.side_effect = ValueError('invalid V24 schema')
        self.host.cleanup_database.return_value = True
        with self.assertRaises(ValueError):
            self.recover()
        self.assertEqual(P.read(self.root/'active.json'), gate)
        self.assertEqual(P.read(self.install/'v24_host.py'), b'old host')
        self.assertEqual(P.read(self.pointer).decode().strip(), self.source_plan['request'])
        failure = P.strict_json(P.read(self.journal.directory/'state.json'))
        self.assertEqual(failure['phase'], 'failed')
        self.assertEqual(failure['containment'], {'database': True})
        self.host.cleanup_database.assert_called_once()

    def test_unconfirmed_db_cleanup_is_recorded_as_failed(self):
        """Do not claim containment when the retry cannot confirm the DB container stopped."""
        self.host.database.side_effect = ValueError('cleanup failed')
        self.host.cleanup_database.return_value = False
        with self.assertRaises(ValueError):
            self.recover()
        self.host.cleanup_database.assert_called_once()
        failure = P.strict_json(P.read(self.journal.directory/'state.json'))
        self.assertEqual(failure['containment'], {'database': False})
        self.assertEqual(P.read(self.root/'active.json'), P.canonical(self.source_state))

    def test_gate_write_interruption_retains_intent_and_both_journals(self):
        """Keep the recorded intent available after an interrupted gate write."""
        active = self.root/'active.json'
        gate = P.read(active)
        replace = P.replace

        def interrupt(path, raw, mode=0o600):
            """Simulate an interruption just before the shared gate changes."""
            if path == active:
                raise OSError('simulated interruption')
            return replace(path, raw, mode)

        with patch.object(P, 'replace', side_effect=interrupt), self.assertRaises(OSError):
            self.recover()
        self.assertEqual(P.read(active), gate)
        self.assertTrue((self.journal.directory/'update.pending.json').is_file())
        with self.assertRaises(ValueError):
            self.journal.load()

    def test_rejects_consumed_fresh_nonce_before_writing_installation(self):
        """Refuse a replay before the repaired host file or pointer is installed."""
        P.create(self.root/('v24-approval-'+self.proof['approval_id']+'.used'), b'previous')
        with self.assertRaises(FileExistsError):
            self.recover()
        self.assertEqual(P.read(self.install/'v24_host.py'), b'old host')
        self.assertFalse(self.journal.directory.exists())

    def test_observation_rejects_missing_snapshot_or_drifted_settings(self):
        """Require both the verified snapshot and the exact configured settings."""
        directory = self.source_journal.directory
        for name, key in (('validate-ec2-config.py', 'old_validator_sha256'),
                          ('fetch-ec2-secrets.py', 'old_fetch_sha256'),
                          ('ec2-deployment-contract.json', 'legacy_contract_sha256')):
            content = name.encode()
            P.create(directory/(name+'.before'), content)
            self.source_plan[key] = P.sha(content)
        self.host.release.image_in_env.return_value = b'candidate settings'
        P.replace(self.env, b'candidate settings')
        self.host.snapshot.return_value = self.source_state['facts']['snapshot_arn']
        with patch.object(H, 'TICKET', self.root/'absent-ticket'):
            args = (P, H, self.host, directory, self.source_plan, self.source_state)
            self.assertEqual(C.observe(*args)[-1], b'candidate settings')
            self.host.snapshot.return_value = None
            with self.assertRaises(ValueError):
                C.observe(*args)
            self.host.snapshot.reset_mock()
            P.replace(self.env, b'changed settings')
            with self.assertRaises(ValueError):
                C.observe(*args)
            self.host.snapshot.assert_not_called()

    def test_authentication_binds_executable_and_plan_bytes(self):
        """Reject tampered tool, plan or signature bytes using a real test key."""
        private, public = self.root/'private.pem', self.root/'public.pem'
        subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048',
                        '-out', str(private)], check=True, capture_output=True)
        subprocess.run(['openssl', 'pkey', '-in', str(private), '-pubout', '-out', str(public)],
                       check=True, capture_output=True)
        tool = Path(C.__file__).read_bytes()
        proof = dict(self.proof, recovery_sha256=P.sha(tool))
        raw = P.canonical(proof)
        signed = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(private)],
                                input=raw, check=True, capture_output=True).stdout
        with patch.multiple(B, PUBLIC_KEY=public, PUBLIC_SHA=P.sha(public.read_bytes())):
            self.assertEqual(C.authenticate(B, raw, signed, self.new_raw, tool), proof)
            for plan_bytes, tool_bytes in ((self.new_raw+b' ', tool), (self.new_raw, tool+b' ')):
                with self.assertRaises(ValueError):
                    C.authenticate(B, raw, signed, plan_bytes, tool_bytes)
            with self.assertRaises(subprocess.CalledProcessError):
                C.authenticate(B, raw, signed[:-1], self.new_raw, tool)
