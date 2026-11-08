"""Isolated Linux/root filesystem and signature tests. No AWS, Docker or real DB calls."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
def load(name, relative):
    spec=importlib.util.spec_from_file_location(name, ROOT/relative)
    module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module
R=load('reconcile', 'scripts/deploy/reconcile-v18.py')
T=load('transition_reconcile_test', 'scripts/deploy/schema-transition.py')
P=load('prepare_reconciliation_test','scripts/ci/prepare-v18-reconciliation.py')
R.T=T


@unittest.skipUnless(sys.platform=='linux' and os.geteuid()==0, 'isolated Linux/root required')
class ReconcileTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='v18-reconcile-test-',dir='/run')
        self.addCleanup(self.tmp.cleanup)
        base=Path(self.tmp.name); self.state=base/'state'; self.plans=base/'plans'
        self.state.mkdir(); self.plans.mkdir(); (self.state/'lock').touch(mode=0o600)
        for obj,name,value in [(T.HOST,'STATE',self.state),(T,'PLANS',self.plans)]:
            p=patch.object(obj,name,value);p.start();self.addCleanup(p.stop)
        self.source_raw=(ROOT/'scripts/tests/fixtures/v18-source-plan.json').read_bytes()
        self.source=json.loads(self.source_raw)
        self.assertEqual(T.HOST.sha(self.source_raw),R.SOURCE_PLAN_SHA)
        (self.plans/(R.SOURCE_REQUEST+'.json')).write_bytes(self.source_raw)
        self.original=self.state/R.SOURCE_REQUEST;self.original.mkdir()
        self.record=dict(request=R.SOURCE_REQUEST,plan_sha256=R.SOURCE_PLAN_SHA,status='recovery-required',terminal_status=False,
            containment_failures=[],phase_approvals={},fence_sha256='f'*64,fence_verified_at='2026-11-08T19:00:00+00:00',
            snapshot_arn='arn:aws:rds:ap-northeast-2:494915081306:snapshot:'+self.source['snapshot'],
            settings_sha256=T.HOST.sha(b'synthetic-settings'),external_operator_acl_recovery_required=True,
            image_id='sha256:'+'a'*64,previous_image_id='sha256:'+'b'*64,redis_id='synthetic-redis',redis_mounts=[])
        for n,phase in enumerate(('quiesce','verify-writers','retire-guard','migrate'),1):
            aid=str(n)*32; sha=str(n)*64
            self.record['phase_approvals'][phase]=dict(id=aid,sha256=sha)
            (self.state/('schema-approval-'+aid+'.used')).write_bytes(R.canonical(dict(request=R.SOURCE_REQUEST,
                phase=phase,plan_sha256=R.SOURCE_PLAN_SHA,approval_sha256=sha)))
        self.persist()
        for name,raw in [('settings.before',b'synthetic-settings'),('history.tsv',('\n'.join(self.source['history'])+'\n').encode()),
                         ('database-policy.tsv',b'postgres\npostgres\nrdsadmin\n')]:
            (self.original/name).write_bytes(raw)
        self.plan=copy.deepcopy(self.source);self.plan.update(request='20261109000000-9',expires_at=int(time.time())+3600)
        self.raw=R.canonical(self.plan);self.sha=T.HOST.sha(self.raw)
        self.proof=dict(format=1,action='reconcile-v18-db-verified',source_request=R.SOURCE_REQUEST,
            source_plan_sha256=R.SOURCE_PLAN_SHA,source_journal_sha256=T.HOST.sha(self.journal),
            target_request=self.plan['request'],target_plan_sha256=self.sha,approval_id='e'*32,
            issued_at=int(time.time())-1,expires_at=self.plan['expires_at'])
        self.report=dict(result='PASS',source_request=R.SOURCE_REQUEST,source_plan_sha256=R.SOURCE_PLAN_SHA,
            target_request=self.plan['request'],target_plan_sha256=self.sha,
            snapshot_arn=self.record['snapshot_arn'],fence_sha256=self.record['fence_sha256'])

    def persist(self):
        self.journal=R.canonical(self.record)
        (self.state/'active.json').write_bytes(self.journal)
        (self.original/'transition.json').write_bytes(self.journal)

    def test_inspect_accepts_historical_expiry_only_as_metadata(self):
        with R.source_lock(),patch.object(T,'validate_plan',side_effect=AssertionError('must not renew old authority')):
            self.assertEqual(R.context()[3],self.journal)

    def test_lock_conflict_blocks(self):
        with R.source_lock():
            with self.assertRaises(BlockingIOError):
                with R.source_lock():pass

    def test_original_plan_tamper_rejected(self):
        (self.plans/(R.SOURCE_REQUEST+'.json')).write_bytes(self.source_raw+b' ')
        with self.assertRaises(ValueError):R.context()

    def test_journal_divergence_and_incomplete_containment_rejected(self):
        (self.state/'active.json').write_bytes(self.journal+b' ')
        with self.assertRaises(ValueError):R.context()
        for field,value in [('status','db-changing'),('terminal_status',True),('containment_failures',['stop-backend'])]:
            old=self.record[field];self.record[field]=value;self.persist()
            with self.assertRaises(ValueError):R.context()
            self.record[field]=old

    def test_missing_consumption_proof_rejected(self):
        (self.state/('schema-approval-'+'4'*32+'.used')).unlink()
        with self.assertRaises(FileNotFoundError):R.context()

    def test_valid_fresh_plan_and_expired_target_rejected(self):
        self.assertEqual(R.continuation(self.raw,self.sha,self.source),self.plan)
        for expiry in (1,int(time.time())+1190,int(time.time())+7300):
            p=dict(self.plan,expires_at=expiry);raw=R.canonical(p)
            with self.assertRaises(T.HOST.ReleaseError):R.continuation(raw,T.HOST.sha(raw),self.source)

    def test_only_identity_and_window_may_change(self):
        for key,value in [('candidate','sha256:'+'c'*64),('snapshot','hobbytree-v5-another'),('history',[]),
                          ('database_owner','other'),('privileged_login_roles',['postgres']),('db_host','other.rds.amazonaws.com')]:
            p=dict(self.plan);p[key]=value;raw=R.canonical(p)
            with self.assertRaises(Exception):R.continuation(raw,T.HOST.sha(raw),self.source)

    def test_request_collision_and_old_request_rejected(self):
        p=dict(self.plan,request=R.SOURCE_REQUEST);raw=R.canonical(p)
        with self.assertRaises(ValueError):R.continuation(raw,T.HOST.sha(raw),self.source)
        (self.state/self.plan['request']).mkdir()
        with self.assertRaises(ValueError):R.continuation(self.raw,self.sha,self.source)

    def test_approval_single_use_and_old_id_rejected(self):
        R.consume(self.proof,R.canonical(self.proof))
        self.assertTrue((self.state/('schema-approval-'+self.proof['approval_id']+'.used')).exists())
        with self.assertRaises(ValueError):R.consume(self.proof,R.canonical(self.proof))
        p=dict(self.proof,approval_id='4'*32)
        with self.assertRaises(ValueError):R.consume(p,R.canonical(p))

    def test_signed_context_rejects_tamper_expiry_extra_fields(self):
        sig=self.plans/'test.sig';sig.write_bytes(b'synthetic signature')
        for key,value in [('source_journal_sha256','0'*64),('action','deploy'),('target_request','123-1'),
                          ('target_plan_sha256','0'*64),('expires_at',1),('issued_at',int(time.time())+30),('extra',True)]:
            p=dict(self.proof);p[key]=value
            with self.assertRaises(ValueError):R.authorize(R.canonical(p),sig,self.plan,self.sha,self.journal,Mock(),time.monotonic()+60)

    def test_real_signature_and_invalid_signature(self):
        private=self.plans/'test-only-private.pem';public=self.plans/'test-only-public.pem'
        body=self.plans/'approval.json';sig=self.plans/'approval.sig';body.write_bytes(R.canonical(self.proof))
        for args in (['genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(private)],
                     ['pkey','-in',str(private),'-pubout','-out',str(public)],
                     ['dgst','-sha256','-sign',str(private),'-out',str(sig),str(body)]):
            subprocess.run(['/usr/bin/openssl',*args],capture_output=True,check=True)
        with patch.object(T,'APPROVAL_KEY',public),patch.object(R,'PUBLIC_SHA',T.HOST.sha(public.read_bytes())):
            commands=T.HOST.Commands(self.tmp.name)
            self.assertEqual(R.authorize(body.read_bytes(),sig,self.plan,self.sha,self.journal,commands,time.monotonic()+60),self.proof)
            sig.write_bytes(b'corrupt signature')
            with self.assertRaises(T.HOST.ReleaseError):R.authorize(body.read_bytes(),sig,self.plan,self.sha,self.journal,commands,time.monotonic()+60)

    def test_probe_sequence_and_no_migration_entry(self):
        seen=[]
        def migrate(engine,mode):seen.append(mode);return self.record['fence_sha256'] if mode=='check-fence' else None
        def aws(engine,*args,**kw):
            if args[0]=='sts':return {'Account':'494915081306'}
            if args[1]=='describe-db-instances':return {'DBInstances':[dict(DBInstanceStatus='available',Endpoint={'Address':self.plan['db_host']},DBInstanceArn='arn:aws:rds:ap-northeast-2:494915081306:db:hobbytree-dev-private')]}
            return {'TargetHealthDescriptions':[{'TargetHealth':{'State':'unused','Reason':'Target.NotRegistered'}}]}
        with patch.object(T.Engine,'protected_inputs'),patch.object(T.Engine,'stopped'),patch.object(T.HOST.Deployment,'verify_target_contract'),\
             patch.object(T.Engine,'aws',aws),patch.object(T.Engine,'snapshot',return_value=self.record['snapshot_arn']),patch.object(T.Engine,'migration',migrate):
            self.assertEqual(R.probes(self.plan,self.record,Mock(),self.tmp.name,self.sha)['result'],'PASS')
        self.assertEqual(seen,['check-fence','check-after','validate-app','check-fence'])
        self.assertNotIn('migrate',seen)

    def test_offline_draft_binds_fresh_plan_and_source_journal(self):
        now=int(time.time())
        proof=P.create(self.source_raw,self.raw,'f'*64,now)
        self.assertEqual(proof['target_plan_sha256'],self.sha)
        self.assertEqual(proof['source_journal_sha256'],'f'*64)
        for source,target,journal in [(self.source_raw+b' ',self.raw,'f'*64),
            (self.source_raw,self.raw,'bad'),(self.source_raw,self.source_raw,'f'*64),
            (self.source_raw,R.canonical(dict(self.plan,candidate='sha256:'+'0'*64)),'f'*64),
            (self.source_raw,R.canonical(dict(self.plan,expires_at=now+60)),'f'*64)]:
            with self.assertRaises(ValueError):P.create(source,target,journal,now)

    def test_fence_mismatch_and_snapshot_mismatch_block_handoff(self):
        def aws(engine,*args,**kw):
            if args[0]=='sts':return {'Account':'494915081306'}
            if args[1]=='describe-db-instances':return {'DBInstances':[dict(DBInstanceStatus='available',Endpoint={'Address':self.plan['db_host']},DBInstanceArn='arn:aws:rds:ap-northeast-2:494915081306:db:hobbytree-dev-private')]}
            return {'TargetHealthDescriptions':[{'TargetHealth':{'State':'unused','Reason':'Target.NotRegistered'}}]}
        with patch.object(T.Engine,'protected_inputs'),patch.object(T.Engine,'stopped'),patch.object(T.HOST.Deployment,'verify_target_contract'),patch.object(T.Engine,'aws',aws):
            with patch.object(T.Engine,'snapshot',return_value='wrong-snapshot'):
                with self.assertRaises(ValueError):R.probes(self.plan,self.record,Mock(),self.tmp.name,self.sha)
            with patch.object(T.Engine,'snapshot',return_value=self.record['snapshot_arn']),patch.object(T.Engine,'migration',return_value='0'*64):
                with self.assertRaises(T.HOST.ReleaseError):R.probes(self.plan,self.record,Mock(),self.tmp.name,self.sha)
        self.assertEqual((self.state/'active.json').read_bytes(),self.journal)

    def test_postcondition_failure_never_publishes_state(self):
        with patch.object(T.Engine,'protected_inputs',side_effect=ValueError('drift')):
            with self.assertRaises(ValueError):R.probes(self.plan,self.record,Mock(),self.tmp.name,self.sha)
        self.assertEqual((self.state/'active.json').read_bytes(),self.journal)
        self.assertFalse((self.state/self.plan['request']).exists())

    def test_each_live_failure_stops_before_any_journal_handoff(self):
        for failure in ('host','alb-contract','account','endpoint','alb-draining','snapshot','check-after','validate-app','fence-final'):
            with self.subTest(failure=failure):
                calls=[]
                def migrate(engine,mode):
                    calls.append(mode)
                    if mode==failure:raise ValueError('injected')
                    if mode=='check-fence':return '0'*64 if failure=='fence-final' and calls.count(mode)==2 else self.record['fence_sha256']
                def aws(engine,*args,**kw):
                    if args[0]=='sts':return {'Account':'wrong' if failure=='account' else '494915081306'}
                    if args[1]=='describe-db-instances':return {'DBInstances':[dict(DBInstanceStatus='available',Endpoint={'Address':'wrong' if failure=='endpoint' else self.plan['db_host']},DBInstanceArn='arn:aws:rds:ap-northeast-2:494915081306:db:hobbytree-dev-private')]}
                    return {'TargetHealthDescriptions':[{'TargetHealth':{'State':'draining' if failure=='alb-draining' else 'unused','Reason':'Target.NotRegistered'}}]}
                with patch.object(T.Engine,'protected_inputs'),patch.object(T.Engine,'stopped',side_effect=ValueError('host') if failure=='host' else None),\
                     patch.object(T.HOST.Deployment,'verify_target_contract',side_effect=ValueError('contract') if failure=='alb-contract' else None),\
                     patch.object(T.Engine,'aws',aws),patch.object(T.Engine,'snapshot',return_value='wrong' if failure=='snapshot' else self.record['snapshot_arn']),patch.object(T.Engine,'migration',migrate):
                    with self.assertRaises(Exception):R.probes(self.plan,self.record,Mock(),self.tmp.name,self.sha)
                self.assertEqual((self.state/'active.json').read_bytes(),self.journal)
                self.assertFalse((self.state/self.plan['request']).exists())

    def perform_handoff(self):
        R.handoff(self.plan,self.raw,self.record,self.journal,self.source_raw,self.proof,
                  R.canonical(self.proof),b'synthetic-signed-by-test',self.report)

    def test_handoff_preserves_original_and_enables_only_normal_next_phase(self):
        self.perform_handoff()
        target=self.state/self.plan['request'];current=json.loads((self.state/'active.json').read_bytes())
        self.assertEqual(current,json.loads((target/'transition.json').read_bytes()))
        self.assertEqual(current['status'],'db-verified');self.assertFalse(current['terminal_status'])
        self.assertEqual((self.original/'transition.json').read_bytes(),self.journal)
        self.assertEqual((self.plans/(R.SOURCE_REQUEST+'.json')).read_bytes(),self.source_raw)
        self.assertEqual((target/'source-journal.json').read_bytes(),self.journal)
        self.assertEqual((target/'settings.before').read_bytes(),b'synthetic-settings')
        with T.locked(self.plan['request'],'verify-activation',self.sha):pass
        for phase in ('migrate','deploy'):
            with self.assertRaises(T.HOST.ReleaseError):
                with T.locked(self.plan['request'],phase,self.sha):pass
        with self.assertRaises(T.HOST.ReleaseError):
            with T.HOST.release_lock():pass

    def test_crash_before_active_publish_preserves_source_and_does_not_retry(self):
        with patch.object(T.HOST,'atomic_write',side_effect=OSError('injected publication failure')):
            with self.assertRaises(OSError):self.perform_handoff()
        self.assertEqual((self.state/'active.json').read_bytes(),self.journal)
        self.assertEqual((self.original/'transition.json').read_bytes(),self.journal)
        with self.assertRaises(ValueError):self.perform_handoff()

    def test_drift_after_assessment_and_invalid_report_rejected(self):
        self.report['result']='FAIL'
        with self.assertRaises(ValueError):self.perform_handoff()
        self.assertFalse((self.state/self.plan['request']).exists())
        self.report['result']='PASS';(self.state/'active.json').write_bytes(self.journal+b' ')
        with self.assertRaises(ValueError):self.perform_handoff()

    def test_source_artifact_drift_during_copy_never_publishes_active(self):
        (self.original/'history.tsv').write_bytes(b'tampered')
        with self.assertRaises(ValueError):self.perform_handoff()
        self.assertEqual((self.state/'active.json').read_bytes(),self.journal)
        self.assertEqual((self.original/'transition.json').read_bytes(),self.journal)


if __name__=='__main__':unittest.main()
