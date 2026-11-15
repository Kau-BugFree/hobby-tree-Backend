"""Isolated recovery admission, provenance, and interrupted publication tests."""
import copy
import fcntl
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from test_v24_transition import P, H, T, module, plan

R = module('v24_recover_quiesce')
RELEASE = module('ec2-release')


@unittest.skipUnless(os.name == 'posix' and os.geteuid() == 0, 'isolated Linux root fixtures only')
class QuiesceRecoveryTests(unittest.TestCase):
    def setUp(self):
        """Build a failed old request and a distinct, same-JAR successor in /run."""
        self.temp = tempfile.TemporaryDirectory(prefix='v24-recovery-test-', dir='/run')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.root.chmod(0o700)
        self.patch_state = patch.object(H, 'STATE', self.root)
        self.patch_state.start()
        self.addCleanup(self.patch_state.stop)
        self.old = plan()
        self.legacy, self.settings = b'{"status":"committed"}', b'original fixture settings'
        self.old['legacy_active_sha256'] = P.sha(self.legacy)
        self.old_raw = P.canonical(self.old)
        self.original = dict(request=self.old['request'], plan_sha256=P.sha(self.old_raw),
            phase='failed', status='v24-failed', failed_phase='quiesce',
            approvals={'prepare':'1'*32, 'quiesce':'2'*32},
            containment={'database':True, 'exclude':False, 'disable':True, 'stop':True},
            facts={'settings_sha256':P.sha(self.settings), 'previous_container_id':'8'*64,
                   'redis_id':'9'*64, 'redis_mounts':[],
                   'database_cleanup':[{'mode':'check-before', 'stopped_or_absent':True}]})
        self.old_dir = self.root/('v24-'+self.old['request'])
        self.old_dir.mkdir(mode=0o700)
        for name, raw in [('plan.json', self.old_raw), ('state.json', P.canonical(self.original)),
                          ('legacy-active.before.json', self.legacy), ('settings.before', self.settings)]:
            P.create(self.old_dir/name, raw)
        P.create(self.root/'active.json', P.canonical(self.original))
        P.create(self.root/'lock', b'')
        old_journal = P.Journal(self.root, self.old['request'], P.sha(self.old_raw))
        for phase, nonce in self.original['approvals'].items():
            body = dict(format=1, action='hobbytree-v18-v24', request=self.old['request'],
                phase=phase, plan_sha256=P.sha(self.old_raw), approval_id=nonce, issued_at=900, expires_at=1800)
            old_journal.authorize_once(dict(phase=P.PHASES[phase][0], approvals={}),
                                       body, P.canonical(body), b'fixture signature')
        self.new = copy.deepcopy(self.old)
        self.new.update(request='c'*32, revision='d'*40, candidate='sha256:'+'9'*64,
                        candidate_image_id='sha256:'+'7'*64, snapshot='hobbytree-v24-'+'c'*32)
        self.new['bundle']['v24_host.py'] = '2'*64
        self.new_raw = P.canonical(self.new)
        self.proof = dict(format=1, action='hobbytree-v24-reconcile-quiesce', request=self.new['request'],
            plan_sha256=P.sha(self.new_raw), recovery_sha256='6'*64,
            source_request=self.old['request'], source_plan_sha256=P.sha(self.old_raw),
            source_state_sha256=P.sha(P.canonical(self.original)), approval_id='f'*32,
            issued_at=900, expires_at=3000)
        self.journal = P.Journal(self.root, self.new['request'], P.sha(self.new_raw))
        self.env = self.root/'settings'
        P.create(self.env, self.settings)
        self.host = Mock(plan=self.new, journal=self.journal, release=Mock(ENV_FILE=self.env))
        self.signature = patch.object(T, 'signature')
        self.verify_signature = self.signature.start()
        self.addCleanup(self.signature.stop)
        self.clock = patch.object(R.time, 'time', return_value=1000)
        self.clock.start()
        self.addCleanup(self.clock.stop)

    def execute(self):
        """Keep cloud/runtime observation at one explicit mock boundary."""
        with patch.object(R, 'observe', return_value='fixture-stopped-at'):
            return R.reconcile(P, H, T, self.host, self.journal, self.proof,
                               P.canonical(self.proof), b'fixture fresh signature', self.new_raw)

    def rewrite_source(self, changed):
        """Rebind altered evidence to test policy independently of hash admission."""
        raw = P.canonical(changed)
        P.replace(self.old_dir/'state.json', raw)
        P.replace(self.root/'active.json', raw)
        self.proof['source_state_sha256'] = P.sha(raw)

    def test_fresh_proof_rejects_wrong_scope_tool_time_and_duplicate_keys(self):
        """A phase approval or unbound recovery cannot authorize this tool."""
        def validate(value):
            """Verify the fixture's exact plan/tool and clock."""
            return R.approval(P, P.canonical(value), self.new, P.sha(self.new_raw), '6'*64, now=1000)
        self.assertEqual(validate(self.proof), self.proof)
        for key, value in [('action','hobbytree-v18-v24'), ('format',True), ('recovery_sha256','0'*64),
                           ('request',self.old['request']), ('source_request',self.new['request']),
                           ('issued_at',1001), ('expires_at',1299), ('expires_at',9000)]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                validate(dict(self.proof, **{key:value}))
        with self.assertRaises(ValueError):
            R.strict(b'{"action":"one","action":"two"}')

    def test_success_preserves_source_and_connects_only_new_quiesced_request(self):
        """Never change the failed source, its signatures, or its original plan."""
        before = {p.name:p.read_bytes() for p in self.old_dir.iterdir()}
        markers = {path:path.read_bytes() for path in self.root.glob('v24-approval-*.used')}
        result = self.execute()
        self.assertEqual(result['phase'], 'quiesced')
        self.assertFalse(result['application_started'])
        self.assertFalse(result['database_writes'])
        self.assertEqual(before, {p.name:p.read_bytes() for p in self.old_dir.iterdir()})
        self.assertEqual(markers, {path:path.read_bytes() for path in markers})
        state = self.journal.load()
        self.assertEqual(state['recovery']['source_state_sha256'], self.proof['source_state_sha256'])
        self.assertEqual(state['approvals'], {'reconcile-quiesce':'f'*32})
        self.assertEqual(P.read(self.journal.directory/'legacy-active.before.json'), self.legacy)
        self.host.database.assert_called_once_with('check-before', state)
        self.assertEqual(self.verify_signature.call_count, 3)
        self.host.perform.assert_not_called()
        self.host.target.assert_not_called()

    def assert_source_nonce_rejected(self):
        """Invalid historical consumption cannot observe or create a successor."""
        with patch.object(R, 'observe', return_value='fixture-stopped-at') as observer, \
             self.assertRaises((ValueError, OSError)):
            R.reconcile(P,H,T,self.host,self.journal,self.proof,
                        P.canonical(self.proof),b'fixture signature',self.new_raw)
        observer.assert_not_called()
        self.assertFalse(self.journal.directory.exists())
        self.assertFalse((self.root/('v24-approval-'+self.proof['approval_id']+'.used')).exists())
        self.assertEqual(P.read(self.root/'active.json'), P.canonical(self.original))
        self.host.database.assert_not_called()

    def test_missing_source_nonce_markers_fail_before_admission(self):
        """Both old phases must retain the global marker written by authorize_once."""
        for nonce in self.original['approvals'].values():
            marker = self.root/('v24-approval-'+nonce+'.used')
            raw = marker.read_bytes()
            marker.unlink()
            try:
                self.assert_source_nonce_rejected()
            finally:
                P.create(marker, raw)

    def test_changed_source_nonce_bindings_fail_before_admission(self):
        """A marker from another request, phase, plan or approval is not evidence."""
        for nonce in self.original['approvals'].values():
            marker = self.root/('v24-approval-'+nonce+'.used')
            raw = marker.read_bytes()
            value = P.strict_json(raw)
            changed = [dict(value, **{key: replacement}) for key, replacement in
                       [('request',self.new['request']), ('phase','backup'),
                        ('plan_sha256','0'*64), ('approval_sha256','0'*64)]]
            changed += [{k:v for k,v in value.items() if k != 'phase'}, dict(value, extra=True)]
            try:
                for bad in changed:
                    P.replace(marker, P.canonical(bad))
                    self.assert_source_nonce_rejected()
                P.replace(marker, b'{"phase":"prepare","phase":"quiesce"}')
                self.assert_source_nonce_rejected()
            finally:
                P.replace(marker, raw)

    def test_unprotected_source_nonce_marker_is_rejected(self):
        """A matching marker still needs protected ownership and a regular path."""
        marker = self.root/('v24-approval-'+self.original['approvals']['prepare']+'.used')
        raw = marker.read_bytes()
        marker.chmod(0o666)
        self.assert_source_nonce_rejected()
        marker.chmod(0o600)
        os.chown(marker, 1, -1)
        self.assert_source_nonce_rejected()
        os.chown(marker, 0, -1)
        marker.unlink()
        target = self.root/'copied-marker'
        P.create(target, raw)
        marker.symlink_to(target)
        self.assert_source_nonce_rejected()

    def test_wrong_signature_is_rejected_before_observation_or_new_request(self):
        """Fresh and historical signatures both gate reconciliation."""
        for calls in ([ValueError('bad signature')], [None, ValueError('bad old signature')]):
            self.verify_signature.side_effect = calls
            with patch.object(R, 'observe') as observer, self.assertRaises(ValueError):
                R.reconcile(P,H,T,self.host,self.journal,self.proof,P.canonical(self.proof),b'bad',self.new_raw)
            observer.assert_not_called()
            self.assertFalse(self.journal.directory.exists())

    def test_success_requires_a_fresh_normal_approval_to_continue(self):
        """The successor enters verify-fence without fabricating earlier approvals."""
        self.execute()
        state = self.journal.load()
        body = dict(format=1,action='hobbytree-v18-v24',request=self.new['request'],phase='verify-fence',
                    plan_sha256=P.sha(self.new_raw),approval_id='e'*32,issued_at=900,expires_at=1800)
        validated = P.validate_approval(P.canonical(body),P.sha(self.new_raw),self.new['request'],'verify-fence')
        self.journal.authorize_once(state,validated,P.canonical(body),b'new fixture signature')
        adapter = Mock()
        adapter.perform.return_value = {'fence_sha256':'5'*64}
        P.execute_phase(self.journal,state,validated,adapter)
        value = self.journal.load()
        self.assertEqual(value['phase'],'fenced')
        self.assertEqual(set(value['approvals']),{'reconcile-quiesce','verify-fence'})
        self.assertEqual(P.read(self.old_dir/'state.json'),P.canonical(self.original))

    def test_later_phases_or_incomplete_containment_are_not_recoverable(self):
        """Only this pre-migration quiesce failure has a supported recovery policy."""
        changes = [dict(failed_phase='migrate'), dict(phase='quiesce-running'),
                   dict(approvals=dict(self.original['approvals'], backup='3'*32)),
                   dict(facts=dict(self.original['facts'], snapshot_arn='unexpected')),
                   dict(containment=dict(self.original['containment'], stop=False))]
        for change in changes:
            with self.subTest(change=change):
                self.rewrite_source(dict(self.original, **change))
                with self.assertRaises(ValueError):
                    self.execute()
                self.assertFalse(self.journal.directory.exists())

    def test_changed_target_jar_or_nonfix_bundle_is_rejected(self):
        """A recovery cannot carry new schema, credentials, or unrelated code."""
        for key, value in [('db_host','other.rds.amazonaws.com'), ('candidate_jar_sha256','0'*64),
                           ('ai_properties_sha256','0'*64), ('before_schema','0'*64)]:
            changed = copy.deepcopy(self.new)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                R.source(P,H,changed,self.proof)
        for filename in ('v24_protocol.py', 'catalogue.sql'):
            changed = copy.deepcopy(self.new)
            changed['bundle'][filename] = '5'*64
            with self.assertRaises(ValueError):
                R.source(P,H,changed,self.proof)

    def test_source_drift_pending_write_and_existing_destination_fail_closed(self):
        """Never guess the old gate or overwrite an existing request."""
        P.replace(self.root/'active.json', b'foreign gate')
        with self.assertRaises(ValueError):
            self.execute()
        P.replace(self.root/'active.json', P.canonical(self.original))
        P.create(self.old_dir/'update.pending.json', b'{}')
        with self.assertRaises(ValueError):
            self.execute()
        (self.old_dir/'update.pending.json').unlink()
        self.journal.directory.mkdir(mode=0o700)
        with self.assertRaises(ValueError):
            self.execute()

    def test_reused_nonce_cannot_create_a_successor(self):
        """Use the same global nonce namespace as ordinary phase approvals."""
        P.create(self.root/('v24-approval-'+'f'*32+'.used'), b'prior use')
        with self.assertRaises(FileExistsError):
            self.execute()
        self.assertFalse(self.journal.directory.exists())
        self.host.database.assert_not_called()

    def test_db_observation_failure_keeps_original_gate_and_records_new_failure(self):
        """Failed read-only checks never promote a recovery to quiesced."""
        self.host.database.side_effect = RuntimeError('read-only observation failed')
        with self.assertRaises(RuntimeError):
            self.execute()
        self.assertEqual(P.read(self.root/'active.json'), P.canonical(self.original))
        value = P.strict_json(P.read(self.journal.directory/'state.json'))
        self.assertEqual(value['failed_phase'], 'reconcile-quiesce')
        self.assertTrue((self.root/('v24-approval-'+'f'*32+'.used')).exists())
        self.host.target.assert_not_called()

    def test_initial_observation_failure_creates_no_request_or_nonce(self):
        """Distinguish pre-admission failure from a later read-only DB check failure."""
        with patch.object(R,'observe',side_effect=ValueError('runtime drift')), self.assertRaises(ValueError):
            R.reconcile(P,H,T,self.host,self.journal,self.proof,P.canonical(self.proof),b'sig',self.new_raw)
        self.assertFalse(self.journal.directory.exists())
        self.assertFalse((self.root/('v24-approval-'+'f'*32+'.used')).exists())
        self.assertEqual(P.read(self.root/'active.json'),P.canonical(self.original))

    def test_interrupted_pair_write_keeps_intent_and_does_not_rewind_gate(self):
        """Retain exact pending publication for inspection under normal journal rules."""
        replace = P.replace
        def interrupt(path, raw, mode=0o600):
            """Simulate interruption after durable intent but before active publication."""
            if path == self.root/'active.json':
                raise InterruptedError('fixture interruption')
            return replace(path,raw,mode)
        with patch.object(P,'replace',side_effect=interrupt), self.assertRaises(InterruptedError):
            self.execute()
        self.assertTrue((self.journal.directory/'update.pending.json').exists())
        self.assertEqual(P.read(self.root/'active.json'), P.canonical(self.original))
        self.assertEqual(P.strict_json(P.read(self.journal.directory/'state.json'))['phase'], 'quiesced')
        self.assertEqual(P.read(self.old_dir/'state.json'), P.canonical(self.original))

    def test_observation_uses_only_queries_and_rejects_existing_snapshot(self):
        """The real observation method performs no start, target change, or DB write."""
        host = self.host
        host.scratch = self.root
        P.create(self.root/'candidate.jar', b'fixture jar')
        host.plan = dict(self.new, candidate_jar_sha256=P.sha(b'fixture jar'))
        host.runtime.previous = 'fixture-old-image'
        host.runtime.image = 'fixture-new-image'
        host.runtime.container.return_value = {'Config':{'Image':'fixture-old-image','Env':['SAFE=value']},
                                               'State':{'FinishedAt':'fixture-finished'}}
        host.compose.return_value = ([], {'environment':{'SAFE':'value'}})
        host.runtime.extract_schema.side_effect = [self.new['before_schema'], self.new['after_schema']]*2
        host.aws.return_value = {'DBSnapshots':[]}
        with patch.multiple(H, INSTALL=self.root/'not-installed', DROPIN=self.root/'no-dropin',
                            POINTER=self.root/'no-pointer', TICKET=self.root/'no-ticket'):
            self.assertEqual(R.observe(P,H,host,self.original,self.old,self.settings), 'fixture-finished')
            host.aws.return_value = {'DBSnapshots':[{'DBSnapshotIdentifier':self.old['snapshot']}]}
            with self.assertRaises(ValueError):
                R.observe(P,H,host,self.original,self.old,self.settings)
        for call in host.run.call_args_list:
            self.assertIn('--check-secrets',call.args[0])
        host.database.assert_not_called()
        host.target.assert_not_called()

    def test_main_refuses_parallel_controller_before_reconciliation(self):
        """A valid request cannot enter recovery while another controller holds the lock."""
        path = self.root/'new-plan.json'
        body, signature = self.root/'recovery.json', self.root/'recovery.sig'
        P.create(path,self.new_raw)
        proof = dict(self.proof,recovery_sha256=P.sha(Path(R.__file__).read_bytes()))
        P.create(body,P.canonical(proof)); P.create(signature,b'fixture')
        with open(self.root/'lock','rb') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            with patch.object(R.sys,'argv',['recover',str(path),str(body),str(signature)]), \
                 patch.object(R,'modules',return_value=(P,H,T)), patch.object(H,'Host',return_value=self.host), \
                 patch.object(R,'checked',side_effect=lambda p,*unused:p.read_bytes()), patch.object(R,'authenticate'), \
                 patch.object(R,'reconcile') as call:
                with self.assertRaises(BlockingIOError):
                    R.main()
                call.assert_not_called()

    def test_stopped_container_privilege_mount_and_port_drift_is_rejected(self):
        """Use the real pinned runtime validator on an otherwise eligible stopped image."""
        host = self.host
        host.scratch = self.root
        P.create(self.root/'candidate.jar',b'fixture jar')
        host.plan = dict(self.new,candidate_jar_sha256=P.sha(b'fixture jar'))
        runtime = RELEASE.Deployment.__new__(RELEASE.Deployment)
        runtime.backend_config = {'volumes':[{'type':'bind','source':'/fixture/secrets','target':'/run/secrets','read_only':True}],
                                  'ports':[{'target':8080,'published':8080,'host_ip':'0.0.0.0'}]}
        host.runtime.backend_runtime.side_effect = runtime.backend_runtime
        host.runtime.previous,host.runtime.image = 'fixture-old-image','fixture-new-image'
        current = {'Config':{'Image':'fixture-old-image','Env':['SAFE=value'],'User':'10001:10001',
                            'Entrypoint':['/app/docker-entrypoint.sh'],'Cmd':None},
                   'HostConfig':{'Memory':1024**3,'MemorySwap':1024**3,'ReadonlyRootfs':True,'Privileged':False,
                                 'PortBindings':{'8080/tcp':[{'HostIp':'0.0.0.0','HostPort':'8080'}]}},
                   'Mounts':[{'Type':'bind','Source':'/fixture/secrets','Destination':'/run/secrets','RW':False}],
                   'State':{'FinishedAt':'fixture-finished'}}
        host.runtime.container.return_value = current
        host.compose.return_value = ([],{'environment':{'SAFE':'value'}})
        host.runtime.extract_schema.side_effect = [self.new['before_schema'],self.new['after_schema']]
        host.aws.return_value = {'DBSnapshots':[]}
        with patch.multiple(H,INSTALL=self.root/'absent-install',DROPIN=self.root/'absent-dropin',
                            POINTER=self.root/'absent-pointer',TICKET=self.root/'absent-ticket'):
            self.assertEqual(R.observe(P,H,host,self.original,self.old,self.settings),'fixture-finished')
            for field,value in [('Privileged',True),('ReadonlyRootfs',False),('Memory',0),('PortBindings',{})]:
                changed = copy.deepcopy(current)
                changed['HostConfig'][field] = value
                host.runtime.container.return_value = changed
                with self.subTest(field=field), self.assertRaises(RELEASE.ReleaseError):
                    R.observe(P,H,host,self.original,self.old,self.settings)
            changed = copy.deepcopy(current)
            changed['Mounts'][0]['RW'] = True
            host.runtime.container.return_value = changed
            with self.assertRaises(RELEASE.ReleaseError):
                R.observe(P,H,host,self.original,self.old,self.settings)
        host.database.assert_not_called()
        host.target.assert_not_called()

    def test_changed_target_group_contract_is_rejected_before_database_work(self):
        """A prior prepare check cannot authorize a changed ALB health contract."""
        self.host.runtime.verify_target_contract.side_effect = ValueError('ALB contract drift')
        with self.assertRaises(ValueError):
            R.observe(P,H,self.host,self.original,self.old,self.settings)
        self.host.database.assert_not_called()
        self.host.target.assert_not_called()

    def test_real_bootstrap_signature_is_verified_before_module_import(self):
        """Tampering cannot execute adjacent Python even when the files are protected."""
        private, public = self.root/'private.pem', self.root/'public.pem'
        subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048',
                        '-out',str(private)],check=True,capture_output=True)
        subprocess.run(['openssl','pkey','-in',str(private),'-pubout','-out',str(public)],check=True,capture_output=True)
        tool = Path(R.__file__).read_bytes()
        proof = dict(self.proof,recovery_sha256=P.sha(tool))
        raw = P.canonical(proof)
        signed = subprocess.run(['openssl','dgst','-sha256','-sign',str(private)],
                                input=raw,check=True,capture_output=True).stdout
        with patch.object(R,'PUBLIC_KEY',public), patch.object(R,'PUBLIC_SHA',P.sha(public.read_bytes())):
            R.authenticate(raw,signed,self.new_raw,tool)
            with self.assertRaises(ValueError):
                R.authenticate(raw+b' ',signed,self.new_raw,tool)
            with self.assertRaises(ValueError):
                R.authenticate(raw,signed,self.new_raw+b' ',tool)
            path, body, sig = self.root/'plan-new.json', self.root/'proof.json', self.root/'proof.sig'
            P.create(path,self.new_raw); P.create(body,raw+b' '); P.create(sig,signed)
            with patch.object(R.sys,'argv',['recover',str(path),str(body),str(sig)]), \
                 patch.object(R,'checked',side_effect=lambda p,*unused:p.read_bytes()), patch.object(R,'modules') as loader:
                with self.assertRaises(ValueError):
                    R.main()
                loader.assert_not_called()


if __name__ == '__main__':
    unittest.main()
