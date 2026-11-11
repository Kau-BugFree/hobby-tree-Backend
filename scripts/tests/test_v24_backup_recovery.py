"""No-network tests for immutable backup-failure recovery and its approval chain."""
import copy
import fcntl
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

from test_v24_transition import P, H, T, module
import test_v24_quiesce_recovery as quiesce_tests

R = module('v24_recover_backup')


@unittest.skipUnless(os.name == 'posix' and os.geteuid() == 0, 'isolated Linux root fixtures only')
class BackupRecoveryTests(unittest.TestCase):
    def setUp(self):
        """Build the real two-generation provenance and consumed approval files."""
        quiesce_tests.QuiesceRecoveryTests.setUp(self)
        self.ancestor, self.ancestor_state = self.old, self.original
        self.ancestor_dir = self.old_dir
        self.old = copy.deepcopy(self.new)
        self.old_raw = P.canonical(self.old)
        previous = dict(self.proof, recovery_sha256=R.QUIESCE_TOOL_SHA)
        self.original = dict(request=self.old['request'], plan_sha256=P.sha(self.old_raw),
            phase='failed', status='v24-failed', failed_phase='backup',
            approvals={'reconcile-quiesce':previous['approval_id'], 'verify-fence':'3'*32, 'backup':'4'*32},
            containment=dict(database=True, exclude=True, disable=True, stop=True),
            facts=dict(self.ancestor_state['facts'], stopped_at='fixture-stop', fence_sha256='5'*64,
                       fence_verified_at='fixture-verified', database_cleanup=[
                           dict(mode='check-before',stopped_or_absent=True),
                           dict(mode='check-fence',stopped_or_absent=True)]),
            recovery={k:previous[k] for k in ('source_request','source_plan_sha256','source_state_sha256','approval_id')})
        self.old_dir = self.root/('v24-'+self.old['request'])
        self.old_dir.mkdir(mode=0o700)
        for name, raw in [('plan.json',self.old_raw),('state.json',P.canonical(self.original)),
                          ('settings.before',self.settings),('legacy-active.before.json',self.legacy),
                          ('recovery.approval.json',P.canonical(previous)),('recovery.approval.sig',b'fixture'),
                          ('recovery-source.plan.json',P.read(self.ancestor_dir/'plan.json')),
                          ('recovery-source.state.json',P.read(self.ancestor_dir/'state.json'))]:
            P.create(self.old_dir/name,raw)
        P.replace(self.root/'active.json',P.canonical(self.original))
        P.create(self.root/('v24-approval-'+previous['approval_id']+'.used'),P.canonical(dict(
            request=self.old['request'],phase='reconcile-quiesce',plan_sha256=P.sha(self.old_raw),
            approval_sha256=P.sha(P.canonical(previous)))))
        old_journal = P.Journal(self.root,self.old['request'],P.sha(self.old_raw))
        for phase in ('verify-fence','backup'):
            body = dict(format=1,action='hobbytree-v18-v24',request=self.old['request'],phase=phase,
                        plan_sha256=P.sha(self.old_raw),approval_id=self.original['approvals'][phase],
                        issued_at=900,expires_at=3000)
            old_journal.authorize_once(dict(phase=P.PHASES[phase][0],approvals={}),body,P.canonical(body),b'fixture')
        self.new = dict(self.old,request='e'*32,snapshot='hobbytree-v24-'+'e'*32)
        self.new_raw = P.canonical(self.new)
        self.proof = dict(format=1,action='hobbytree-v24-reconcile-backup',request=self.new['request'],
            plan_sha256=P.sha(self.new_raw),recovery_sha256='6'*64,source_request=self.old['request'],
            source_plan_sha256=P.sha(self.old_raw),source_state_sha256=P.sha(P.canonical(self.original)),
            approval_id='7'*32,issued_at=900,expires_at=3000)
        self.journal = P.Journal(self.root,self.new['request'],P.sha(self.new_raw))
        self.host.plan,self.host.journal = self.new,self.journal
        self.host.database.side_effect = lambda mode,state: '5'*64 if mode=='check-fence' else None
        self.host.aws.return_value = {'DBSnapshots':[]}

    def execute(self):
        """Mock only cloud observations and database calls; retain real journals."""
        with patch.object(R,'observe',return_value='fixture-stop'):
            return R.reconcile(P,H,T,self.host,self.journal,self.proof,P.canonical(self.proof),b'fresh',self.new_raw)

    def rewrite_source(self, value):
        """Bind a malformed source hash to test admission beyond digest checks."""
        raw=P.canonical(value)
        P.replace(self.old_dir/'state.json',raw);P.replace(self.root/'active.json',raw)
        self.proof['source_state_sha256']=P.sha(raw)

    def assert_refused_before_observation(self):
        """Rejection must not create a nonce, request, or DB connection."""
        with patch.object(R,'observe') as observer, self.assertRaises((ValueError,OSError)):
            R.reconcile(P,H,T,self.host,self.journal,self.proof,P.canonical(self.proof),b'fresh',self.new_raw)
        observer.assert_not_called()
        self.assertFalse(self.journal.directory.exists())
        self.assertFalse((self.root/('v24-approval-'+self.proof['approval_id']+'.used')).exists())
        self.host.database.assert_not_called()

    def test_success_keeps_both_failed_generations_and_rechecks_fence(self):
        """Only a new quiesced gate is published; backup never appears successful."""
        originals={p:p.read_bytes() for d in (self.old_dir,self.ancestor_dir) for p in d.iterdir()}
        markers={p:p.read_bytes() for p in self.root.glob('v24-approval-*.used')}
        result=self.execute()
        self.assertEqual(result['phase'],'quiesced')
        self.assertFalse(result['application_started']);self.assertFalse(result['database_writes'])
        self.assertEqual(originals,{p:p.read_bytes() for p in originals})
        self.assertEqual(markers,{p:p.read_bytes() for p in markers})
        state=self.journal.load()
        self.assertEqual(state['approvals'],{'reconcile-backup':'7'*32})
        self.assertNotIn('fence_sha256',state['facts'])
        self.assertNotIn('snapshot_arn',state['facts'])
        self.assertEqual([c.args[0] for c in self.host.database.call_args_list],['check-before','check-fence'])
        self.assertEqual(self.verify_signature.call_count,6)
        self.host.perform.assert_not_called();self.host.target.assert_not_called()
        self.assertTrue(all(c.args[:2]==('rds','describe-db-snapshots') for c in self.host.aws.call_args_list))
        # The ordinary protocol demands a fresh verify-fence, not backup/migrate.
        for phase in ('backup','migrate'):
            with self.assertRaises(ValueError):
                self.journal.authorize_once(state,dict(phase=phase,approval_id='8'*32),b'x',b'y')

    def test_only_request_and_snapshot_may_change(self):
        """An independent recovery cannot smuggle in another release or policy."""
        for key,value in [('revision','a'*40),('candidate','sha256:'+'a'*64),('candidate_image_id','sha256:'+'b'*64),
                          ('candidate_jar_sha256','a'*64),('after_schema','a'*64),('ai_private_ip','10.1.2.3'),
                          ('bundle',dict(self.new['bundle'],**{'v24_host.py':'a'*64}))]:
            with self.subTest(key=key):
                original=self.host.plan;self.host.plan=dict(self.new,**{key:value})
                self.assert_refused_before_observation();self.host.plan=original

    def test_wrong_failure_containment_and_database_work_are_rejected(self):
        """Refuse any evidence of migration or incomplete containment."""
        variants=[dict(self.original,failed_phase='migrate'),dict(self.original,phase='fenced'),
                  dict(self.original,containment=dict(self.original['containment'],exclude=False)),
                  dict(self.original,facts=dict(self.original['facts'],snapshot_arn='existing')),
                  dict(self.original,facts=dict(self.original['facts'],database_cleanup=[dict(mode='migrate',stopped_or_absent=True)])),
                  dict(self.original,facts=dict(self.original['facts'],database_cleanup=[dict(mode='check-fence',stopped_or_absent=False)])),
                  dict(self.original,approvals=dict(self.original['approvals'],migrate='9'*32))]
        for value in variants:
            with self.subTest(value=value):
                self.rewrite_source(value);self.assert_refused_before_observation()
        self.rewrite_source(self.original)

    def test_pending_pair_update_is_not_repaired_implicitly(self):
        """A recovery request is not permission to finish an unrelated journal write."""
        P.create(self.old_dir/'update.pending.json',b'{}')
        self.assert_refused_before_observation()

    def test_every_consumed_nonce_is_required_and_bound(self):
        """All five historical approvals retain exact protected consumption markers."""
        for marker in self.root.glob('v24-approval-*.used'):
            raw=marker.read_bytes();value=json.loads(raw)
            marker.unlink()
            try:self.assert_refused_before_observation()
            finally:P.create(marker,raw)
            for changed in (dict(value,phase='migrate'),dict(value,approval_sha256='0'*64),dict(value,extra=True)):
                P.replace(marker,P.canonical(changed));self.assert_refused_before_observation()
            P.replace(marker,raw)

    def test_marker_permissions_and_symlink_are_rejected(self):
        """Correct content does not make an unprotected marker trustworthy."""
        marker=next(self.root.glob('v24-approval-*.used'));raw=marker.read_bytes()
        marker.chmod(0o666);self.assert_refused_before_observation();marker.chmod(0o600)
        marker.unlink();copy_path=self.root/'marker-copy';P.create(copy_path,raw);marker.symlink_to(copy_path)
        self.assert_refused_before_observation()

    def test_ancestor_and_recovery_provenance_drift_are_rejected(self):
        """Both source copies and live original evidence must match signed hashes."""
        paths=[self.old_dir/'recovery-source.state.json',self.ancestor_dir/'state.json',self.old_dir/'recovery.approval.json']
        for path in paths:
            raw=path.read_bytes();value=json.loads(raw)
            if path.name=='recovery.approval.json':value['recovery_sha256']='0'*64
            else:value['phase']='quiesced'
            P.replace(path,P.canonical(value));self.assert_refused_before_observation();P.replace(path,raw)

    def test_each_bad_signature_stops_before_observation(self):
        """Fresh recovery plus all five previous signatures are admission gates."""
        for i in range(6):
            self.verify_signature.side_effect=[None]*i+[ValueError('fixture invalid signature')]
            self.assert_refused_before_observation()
        self.verify_signature.side_effect=None

    def test_fence_drift_keeps_active_gate_and_records_only_new_failure(self):
        """No grant restoration or schema repair is attempted on a failed read."""
        before=P.read(self.root/'active.json')
        self.host.database.side_effect=lambda mode,state:'0'*64 if mode=='check-fence' else None
        with self.assertRaises(ValueError):self.execute()
        self.assertEqual(P.read(self.root/'active.json'),before)
        self.assertEqual(json.loads(P.read(self.journal.directory/'state.json'))['failed_phase'],'reconcile-backup')
        self.host.perform.assert_not_called();self.host.target.assert_not_called()

    def test_database_error_keeps_original_gate(self):
        """A failed read remains recorded on the new request without publishing it."""
        before=P.read(self.root/'active.json');self.host.database.side_effect=RuntimeError('fixture database unavailable')
        with self.assertRaises(RuntimeError):self.execute()
        self.assertEqual(P.read(self.root/'active.json'),before)

    def test_preexisting_ancestor_snapshot_is_not_adopted(self):
        """Do not infer that an unrecorded snapshot is a valid completed backup."""
        self.host.aws.side_effect=self.snapshot_on_other_database(self.ancestor['snapshot'])
        with self.assertRaises(ValueError):self.execute()
        self.assertFalse(self.journal.directory.exists());self.host.database.assert_not_called()
        self.assertFalse((self.root/('v24-approval-'+self.proof['approval_id']+'.used')).exists())

    def snapshot_on_other_database(self, identifier):
        """Model RDS filtering: a DB filter hides an identifier collision elsewhere."""
        def aws(service, operation, *args):
            row={'DBSnapshotIdentifier':identifier,'DBInstanceIdentifier':'another-db'}
            if '--db-instance-identifier' in args:
                return {'DBSnapshots':[]}
            self.assertEqual((service,operation),('rds','describe-db-snapshots'))
            requested=args[args.index('--filters')+1].removeprefix('Name=db-snapshot-id,Values=').split(',')
            return {'DBSnapshots':[row] if identifier in requested else []}
        return aws

    def test_real_observation_rejects_old_and_new_snapshots(self):
        """Execute the actual observation function against bounded fake adapters."""
        host=self.host;host.scratch=self.root
        P.create(self.root/'candidate.jar',b'fixture jar')
        host.plan=dict(self.new,candidate_jar_sha256=P.sha(b'fixture jar'))
        host.runtime.previous,host.runtime.image='previous-image','candidate-image'
        host.runtime.container.return_value={'Config':{'Image':'previous-image','Env':['SAFE=value']},'State':{'FinishedAt':'stopped'}}
        host.compose.return_value=([],{'environment':{'SAFE':'value'}})
        host.runtime.extract_schema.side_effect=lambda image,label:host.plan['before_schema' if label=='previous' else 'after_schema']
        with patch.multiple(H,INSTALL=self.root/'absent-install',DROPIN=self.root/'absent-dropin',
                            POINTER=self.root/'absent-pointer',TICKET=self.root/'absent-ticket'):
            self.assertEqual(R.observe(P,H,host,self.original,self.old,self.settings),'stopped')
            for snapshot in (self.old['snapshot'],self.new['snapshot']):
                host.aws.side_effect=self.snapshot_on_other_database(snapshot)
                with self.subTest(snapshot=snapshot),self.assertRaises(ValueError):
                    R.observe(P,H,host,self.original,self.old,self.settings)
                self.assertFalse(self.journal.directory.exists())
                self.assertFalse((self.root/('v24-approval-'+self.proof['approval_id']+'.used')).exists())
        host.database.assert_not_called();host.target.assert_not_called()

    def test_observation_failure_does_not_consume_nonce(self):
        """An unavailable AI/ALB/image observation is not a new recovery attempt."""
        with patch.object(R,'observe',side_effect=ValueError('fixture drift')),self.assertRaises(ValueError):
            R.reconcile(P,H,T,self.host,self.journal,self.proof,P.canonical(self.proof),b'fresh',self.new_raw)
        self.assertFalse(self.journal.directory.exists())
        self.assertFalse((self.root/('v24-approval-'+self.proof['approval_id']+'.used')).exists())

    def test_expiry_after_database_checks_prevents_gate_publication(self):
        """Successful reads cannot publish after the proof's continuation window."""
        def database(mode,state):
            if mode=='check-fence':
                R.time.time.return_value=2999
                return '5'*64
            return None
        self.host.database.side_effect=database
        with self.assertRaises(ValueError):self.execute()
        self.assertEqual(P.read(self.root/'active.json'),P.canonical(self.original))

    def test_nonce_reuse_and_gate_drift_stop_replay(self):
        """No second execution may consume the same proof or overwrite its request."""
        self.execute()
        with self.assertRaises(ValueError):self.execute()

    def test_pair_write_interruption_preserves_intent(self):
        """Never replace a partial published pair with an invented rollback state."""
        original=P.replace
        def replace(path,raw,mode=0o600):
            if path==self.root/'active.json':raise OSError('fixture interruption')
            return original(path,raw,mode)
        with patch.object(P,'replace',side_effect=replace),self.assertRaises(OSError):self.execute()
        self.assertTrue((self.journal.directory/'update.pending.json').exists())
        self.assertEqual(P.read(self.root/'active.json'),P.canonical(self.original))

    def test_fresh_approval_rejects_wrong_action_binding_and_time(self):
        """A quiesce approval or an expired backup recovery proof is insufficient."""
        validate=lambda proof:R.approval(P,P.canonical(proof),self.new,P.sha(self.new_raw),'6'*64,now=1000)
        self.assertEqual(validate(self.proof),self.proof)
        for key,value in [('action','hobbytree-v24-reconcile-quiesce'),('format',True),('recovery_sha256','0'*64),
                          ('source_request',self.new['request']),('issued_at',1001),('expires_at',1299),('expires_at',9000)]:
            with self.subTest(key=key),self.assertRaises(ValueError):validate(dict(self.proof,**{key:value}))

    def test_parallel_controller_is_rejected(self):
        """The global deployment lock serializes recovery with normal phases."""
        path,body,sig=self.root/'new-plan',self.root/'proof',self.root/'signature'
        P.create(path,self.new_raw)
        proof=dict(self.proof,recovery_sha256=P.sha(Path(R.__file__).read_bytes()))
        P.create(body,P.canonical(proof));P.create(sig,b'fixture')
        with open(self.root/'lock','rb') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            with patch.object(R.sys,'argv',['recover',str(path),str(body),str(sig)]),patch.object(R,'authenticate'), \
                 patch.object(R,'modules',return_value=(P,H,T)),patch.object(H,'Host',return_value=self.host), \
                 patch.object(R,'checked',side_effect=lambda p,*unused:p.read_bytes()),patch.object(R,'reconcile') as call:
                with self.assertRaises(BlockingIOError):R.main()
                call.assert_not_called()

    def test_real_signature_tampering_prevents_module_loading(self):
        """Verify RSA before loading any adjacent protocol module."""
        private,public=self.root/'private.pem',self.root/'public.pem'
        subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(private)],check=True,capture_output=True)
        subprocess.run(['openssl','pkey','-in',str(private),'-pubout','-out',str(public)],check=True,capture_output=True)
        tool=Path(R.__file__).read_bytes();proof=dict(self.proof,recovery_sha256=P.sha(tool));raw=P.canonical(proof)
        signature=subprocess.run(['openssl','dgst','-sha256','-sign',str(private)],input=raw,check=True,capture_output=True).stdout
        with patch.object(R,'PUBLIC_KEY',public),patch.object(R,'PUBLIC_SHA',P.sha(public.read_bytes())):
            R.authenticate(raw,signature,self.new_raw,tool)
            with self.assertRaises(ValueError):R.authenticate(raw+b' ',signature,self.new_raw,tool)
            path,body,sig=self.root/'plan-new',self.root/'proof-new',self.root/'sig-new'
            P.create(path,self.new_raw);P.create(body,raw+b' ');P.create(sig,signature)
            with patch.object(R.sys,'argv',['recover',str(path),str(body),str(sig)]), \
                 patch.object(R,'checked',side_effect=lambda p,*unused:p.read_bytes()),patch.object(R,'modules') as loader:
                with self.assertRaises(ValueError):R.main()
                loader.assert_not_called()


if __name__=='__main__':
    unittest.main()
