"""Protocol, replay and failure-containment tests. No cloud access or real secrets."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile
from unittest.mock import Mock, patch

DEPLOY = Path(__file__).resolve().parents[1]/'deploy'


def module(name):
    spec=importlib.util.spec_from_file_location(name,DEPLOY/(name+'.py'))
    value=importlib.util.module_from_spec(spec)
    sys.modules[name]=value
    spec.loader.exec_module(value)
    return value


P=module('v24_protocol')
H=module('v24_host')
T=module('v24_transition')
B=module('prepare-v24-bundle')


def plan():
    value=dict(format=1,request='a'*32,revision='b'*40,candidate='sha256:'+'c'*64,
        candidate_image_id='sha256:'+'d'*64,previous='sha256:'+'e'*64,previous_image_id='sha256:'+'f'*64,
        db_host='local-fixture.ap-northeast-2.rds.amazonaws.com',app_user='ci_app',migration_user='ci_migration',
        database_owner='postgres',privileged_logins=['postgres'],snapshot='hobbytree-v24-'+'a'*32,
        bundle={name:'1'*64 for name in P.ARTIFACTS},history=[f'{i}\tV{i}__fixture.sql\t{i}' for i in range(1,25)],
        account='123456789012',region='ap-northeast-2',instance='i-'+'1'*17,rds='hobbytree-dev-private',ai_private_ip='10.0.0.12')
    for key in ('candidate_jar_sha256','before_schema','legacy_active_sha256','legacy_guard_sha256',
                'legacy_dropin_sha256','legacy_contract_sha256','old_validator_sha256','old_fetch_sha256',
                'ai_properties_sha256','catalogue_source_sha256'):
        value[key]='1'*64
    value['after_schema']='2'*64
    return value


def proof(phase,nonce='b'*32):
    return dict(format=1,action='hobbytree-v18-v24',request='a'*32,phase=phase,plan_sha256='c'*64,
                approval_id=nonce,issued_at=900,expires_at=1800)


class PlanTests(unittest.TestCase):
    def test_exact_plan_and_bound_phase(self):
        self.assertEqual(P.validate_plan(plan()),plan())
        P.validate_approval(P.canonical(proof('prepare')),'c'*64,'a'*32,'prepare',now=1000)

    def test_unknown_paths_and_missing_artifacts_are_rejected(self):
        for name in ('bundle','history','candidate'):
            value=plan()
            if name=='bundle':
                value[name]['../../other']='1'*64
            elif name=='history':
                value[name][18]=value[name][17]
            else:
                value[name]=value['previous']
            with self.assertRaises(ValueError):
                P.validate_plan(value)

    def test_no_public_loopback_or_linklocal_ai_endpoint(self):
        for ip in ('127.0.0.1','169.254.169.254','8.8.8.8','::1'):
            value=plan()
            value['ai_private_ip']=ip
            with self.assertRaises(ValueError):
                P.validate_plan(value)

    def test_wrong_phase_plan_expiry_or_request(self):
        for key,value in [('phase','migrate'),('plan_sha256','d'*64),('expires_at',1200),('request','d'*32)]:
            item=proof('prepare')
            item[key]=value
            with self.assertRaises(ValueError):
                P.validate_approval(P.canonical(item),'c'*64,'a'*32,'prepare',now=1000)

    def test_duplicate_json_keys_rejected(self):
        with self.assertRaises(ValueError):
            P.strict_json(b'{"phase":"prepare","phase":"migrate"}')

    def test_boolean_is_not_a_format_version(self):
        value=plan()
        value['format']=True
        with self.assertRaises(ValueError):
            P.validate_plan(value)
        value=proof('prepare')
        value['format']=True
        with self.assertRaises(ValueError):
            P.validate_approval(P.canonical(value),'c'*64,'a'*32,'prepare',now=1000)


@unittest.skipUnless(os.name=='posix' and os.geteuid()==0,'isolated Linux root fixture only')
class JournalTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='v24-test-',dir='/run')
        self.root=Path(self.temp.name)
        self.legacy=P.canonical({'status':'succeeded','request':'old'})
        P.create(self.root/'active.json',self.legacy)
        P.create(self.root/'old-audit.json',b'preserve all prior approvals\n')
        self.plan=plan()
        self.plan['legacy_active_sha256']=P.sha(self.legacy)
        self.raw=P.canonical(self.plan)
        self.journal=P.Journal(self.root,self.plan['request'],P.sha(self.raw))
        self.state=self.journal.begin(self.plan,self.raw)

    def tearDown(self):
        self.temp.cleanup()

    def authorize(self,phase,nonce='b'*32):
        item=proof(phase,nonce)
        item['plan_sha256']=self.journal.plan_hash
        self.journal.authorize_once(self.state,item,P.canonical(item),b'synthetic-signature')
        return item

    def test_prepare_does_not_replace_legacy_gate(self):
        item=self.authorize('prepare')
        adapter=Mock()
        adapter.perform.return_value={}
        P.execute_phase(self.journal,self.state,item,adapter)
        self.assertEqual(P.read(self.root/'active.json'),self.legacy)
        self.assertEqual(P.read(self.journal.directory/'legacy-active.before.json'),self.legacy)

    def test_each_failure_blocks_forward_progress_without_rollback(self):
        for number,phase in enumerate(P.PHASES):
            with self.subTest(phase=phase):
                self.state['phase']=P.PHASES[phase][0]
                self.journal.save(self.state,block_legacy=phase!='prepare')
                item=self.authorize(phase,f'{number+1:032x}')
                adapter=Mock()
                adapter.perform.side_effect=InterruptedError('injected after possible mutation')
                adapter.contain.return_value={'stop':True,'exclude':True,'disable':True}
                with self.assertRaises(InterruptedError):
                    P.execute_phase(self.journal,self.state,item,adapter)
                self.assertEqual(self.state['phase'],'failed')
                self.assertEqual(self.state['failed_phase'],phase)
                self.assertEqual(adapter.contain.call_count,0 if phase in ('prepare','catalogue') else 1)
                self.assertEqual(P.read(self.root/'old-audit.json'),b'preserve all prior approvals\n')
                if phase!='prepare':
                    self.assertEqual(P.strict_json(P.read(self.root/'active.json'))['status'],'v24-failed')
                with self.assertRaises(ValueError):
                    self.authorize(phase,f'{number+100:032x}')

    def test_replayed_nonce_rejected_before_new_phase_action(self):
        self.authorize('prepare')
        with self.assertRaises(FileExistsError):
            self.authorize('prepare')

    def test_shared_journal_disagreement_rejected(self):
        self.state['phase']='quiesced'
        self.journal.save(self.state)
        P.replace(self.root/'active.json',self.legacy)
        with self.assertRaises(ValueError):
            self.journal.load()

    def test_new_request_does_not_overwrite_previous_directory(self):
        with self.assertRaises(ValueError):
            self.journal.begin(self.plan,self.raw)

    def test_symlinks_and_writable_inputs_are_rejected(self):
        path=self.root/'bad'
        path.symlink_to(self.root/'active.json')
        with self.assertRaises(ValueError):
            P.read(path)
        path.unlink()
        path.write_text('unsafe')
        path.chmod(0o666)
        with self.assertRaises(ValueError):
            P.read(path)

    def test_all_forward_phases_and_two_independent_restarts(self):
        adapter=Mock()
        adapter.perform.return_value={}
        for number,phase in enumerate([*P.PHASES,'restart']):
            item=self.authorize(phase,f'{number+1:032x}')
            P.execute_phase(self.journal,self.state,item,adapter)
        self.assertEqual(self.state['phase'],'complete')
        self.assertEqual(len(list(self.root.glob('v24-approval-*.used'))),len(P.PHASES)+1)
        self.assertEqual(P.read(self.journal.directory/'legacy-active.before.json'),self.legacy)

    def test_signature_verification_rejects_tampered_body(self):
        private,public,sig=(self.root/name for name in ('private.pem','public.pem','signature'))
        subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(private)],
                       check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['openssl','pkey','-in',str(private),'-pubout','-out',str(public)],
                       check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        body=P.canonical(proof('prepare'))
        subprocess.run(['openssl','dgst','-sha256','-sign',str(private),'-out',str(sig)],input=body,check=True,capture_output=True)
        host=Mock(scratch=self.root)
        host.run.side_effect=lambda argv,timeout,data: subprocess.run(argv,input=data,check=True,capture_output=True).stdout
        with patch.object(H,'PUBLIC_KEY',public),patch.object(H,'PUBLIC_SHA',P.sha(public.read_bytes())):
            T.signature(host,body,sig.read_bytes())
            with self.assertRaises(subprocess.CalledProcessError):
                T.signature(host,body+b' ',sig.read_bytes())


class AdapterOrderTests(unittest.TestCase):
    def test_migration_failure_never_reaches_configuration_or_activation(self):
        host=H.Host.__new__(H.Host)
        host.plan=plan()
        host.runtime=Mock()
        host.bundle_check=Mock()
        host.identity=Mock()
        host.fixed_host=Mock()
        host.stopped=Mock()
        host.check_fence=Mock()
        host.snapshot=Mock(return_value='snapshot')
        host.journal=Mock(directory=Path('/test'))
        host.release=Mock(ENV_FILE=Path('/test/settings'))
        host.database=Mock(side_effect=[None,RuntimeError('migration failed')])
        state={'facts':{'snapshot_arn':'snapshot'}}
        with patch.object(H,'read',return_value=b'same settings'):
            with self.assertRaises(RuntimeError):
                host.perform('migrate',state)
        self.assertEqual([c.args[0] for c in host.database.call_args_list],['check-ready','migrate'])

    def test_stop_refuses_a_replaced_container_or_wrong_nonce(self):
        host=Mock(plan=plan())
        host.runtime.image='repo@'+host.plan['candidate']
        valid={'Id':'8'*64,'Image':host.plan['candidate_image_id'],'Name':'/hobbytree-backend-1',
            'HostConfig':{'RestartPolicy':{'Name':'no'}},
            'Config':{'Image':host.runtime.image,'Labels':{'hobbytree.v24.request':host.plan['request'],
                'hobbytree.v24.nonce':'9'*32}}}
        ledger={'container_id':'8'*64,'approval_id':'9'*32}
        host.runtime.container.return_value=valid
        self.assertEqual(T.owned(host,ledger),valid)
        for field,value in [('Id','0'*64),('Image','sha256:'+'0'*64),('Name','/redis')]:
            changed=copy.deepcopy(valid)
            changed[field]=value
            host.runtime.container.return_value=changed
            with self.assertRaises(ValueError):
                T.owned(host,ledger)
        changed=copy.deepcopy(valid)
        changed['Config']['Labels']['hobbytree.v24.nonce']='0'*32
        host.runtime.container.return_value=changed
        with self.assertRaises(ValueError):
            T.owned(host,ledger)
        host.run.assert_not_called()

    def test_effective_unit_rejects_injected_prestart_or_environment(self):
        host=H.Host.__new__(H.Host)
        values={'LoadState':'loaded','Type':'oneshot','RemainAfterExit':'yes','Restart':'no',
            'FragmentPath':str(H.UNIT_FILE),'DropInPaths':str(H.OLD_DROPIN)+' '+str(H.DROPIN),
            'Environment':'','NeedDaemonReload':'no','ExecReload':'','WorkingDirectory':str(H.ROOT),
            'PassEnvironment':'','UnsetEnvironment':'','EnvironmentFiles':'','ExecCondition':'',
            'ExecStartPre':'','ExecStartPost':''}
        for key,action in [('ExecStart','hook-start'),('ExecStop','hook-stop'),('ExecStopPost','hook-cleanup')]:
            values[key]='{ path=/usr/bin/python3 ; argv[]=/usr/bin/python3 -B -I '+str(H.INSTALL/'v24_transition.py')+' '+action+' ; ignore_errors=no ; pid=0 }'
        host.run=Mock(side_effect=lambda *args,**kwargs:'\n'.join(k+'='+v for k,v in values.items()).encode())
        host.verify_unit()
        for key in ('ExecStartPre','Environment','PassEnvironment','EnvironmentFiles'):
            values[key]='injected'
            with self.assertRaises(ValueError):
                host.verify_unit()
            values[key]=''


@unittest.skipUnless(os.name=='posix' and os.geteuid()==0,'isolated Linux root fixture only')
class HookTests(unittest.TestCase):
    setUp = JournalTests.setUp
    tearDown = JournalTests.tearDown

    def test_timed_out_migration_stops_only_its_exact_owned_container(self):
        cid='4'*64
        path=self.root/'db.cid'
        P.create(path,cid.encode())
        host=H.Host.__new__(H.Host)
        host.plan=self.plan
        host.runtime=Mock(image='repo@'+self.plan['candidate'])
        info={'Id':cid,'Image':self.plan['candidate_image_id'],'State':{'Running':True},
            'Config':{'Image':host.runtime.image,'Labels':{'hobbytree.v24.request':self.plan['request'],
                'hobbytree.v24.db-mode':'migrate'}}}
        host.run=Mock(side_effect=[(cid+'\n').encode(),P.canonical([info]),b''])
        host.stop_database(path,'migrate')
        self.assertEqual(host.run.call_args.args[0],['docker','stop','--time','20',cid])
        info['Config']['Labels']['hobbytree.v24.request']='0'*32
        host.run=Mock(side_effect=[(cid+'\n').encode(),P.canonical([info])])
        with self.assertRaises(ValueError):
            host.stop_database(path,'migrate')
        self.assertEqual(host.run.call_count,2)
    def prepare_hook(self):
        nonce='c'*32
        self.state['phase']='activate-running'
        self.state['approvals']['activate']=nonce
        self.state['facts']={'previous_container_id':'6'*64,'candidate_settings_sha256':P.sha(b'settings')}
        self.journal.save(self.state)
        self.pointer=self.root/'pointer'
        self.ticket=self.root/'ticket'
        P.create(self.pointer,(self.plan['request']+'\n').encode())
        P.create(self.root/'lock',b'lock')
        ticket=dict(request=self.plan['request'],plan_sha256=self.journal.plan_hash,phase='activate',
            approval_id=nonce,boot_id=Path('/proc/sys/kernel/random/boot_id').read_text().strip(),
            verified_at=int(time.time()),bundle=str(self.root))
        P.create(self.ticket,P.canonical(ticket))
        item=proof('activate',nonce)
        item.update(plan_sha256=self.journal.plan_hash,issued_at=int(time.time()),expires_at=int(time.time())+1000)
        body=P.canonical(item)
        P.create(self.journal.directory/('activate-'+nonce+'.approval.json'),body)
        P.create(self.journal.directory/('activate-'+nonce+'.approval.sig'),b'fixture')
        P.create(self.root/('v24-approval-'+nonce+'.used'),P.canonical(dict(request=self.plan['request'],phase='activate',
            plan_sha256=self.journal.plan_hash,approval_sha256=P.sha(body))))
        host=Mock(plan=self.plan,deadline=time.monotonic()+200)
        host.runtime.image='repo@'+self.plan['candidate']
        host.release.ENV_FILE=self.root/'settings'
        P.create(host.release.ENV_FILE,b'settings')
        host.compose.return_value=(['compose'],{'environment':{}})
        host.image_check.return_value={'Config':{'Env':[]}}
        previous={'Id':'6'*64,'Image':self.plan['previous_image_id'],'State':{'Running':False},
            'HostConfig':{'RestartPolicy':{'Name':'no'}}}
        created={'Id':'7'*64,'Image':self.plan['candidate_image_id'],'Name':'/hobbytree-backend-1',
            'State':{'Running':False,'Status':'created'},
            'Config':{'Image':host.runtime.image,'Env':[],'Labels':{
                'hobbytree.v24.request':self.plan['request'],'hobbytree.v24.nonce':nonce}},
            'HostConfig':{'RestartPolicy':{'Name':'no'},'CapDrop':['ALL'],'CapAdd':[],
                'SecurityOpt':['no-new-privileges']}}
        healthy=copy.deepcopy(created)
        healthy['State']={'Running':True,'Status':'running','Health':{'Status':'healthy'}}
        host.runtime.container.side_effect=[previous,created,created,healthy]
        return host,ticket

    def invoke(self, action, host):
        with patch.object(H,'POINTER',self.pointer),patch.object(H,'TICKET',self.ticket),patch.object(H,'STATE',self.root),\
             patch.object(H,'Host',return_value=host),patch.object(T,'signature'),patch.dict(os.environ,{'INVOCATION_ID':'d'*32}):
            T.hook(action,P,H)

    def test_start_burns_ticket_before_create_and_requires_fresh_controller(self):
        import fcntl
        host,_=self.prepare_hook()
        host.run.side_effect=lambda *args,**kwargs:self.assertFalse(self.ticket.exists())
        with open(self.root/'lock','rb') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            self.invoke('hook-start',host)
        self.assertEqual(host.run.call_args_list[-1].args[0],['docker','container','start','7'*64])
        self.assertEqual(len(list(self.journal.directory.glob('backend-*.json'))),1)
        with self.assertRaises(FileNotFoundError):
            self.invoke('hook-start',host)

    def test_stale_ticket_and_idle_controller_never_create_a_container(self):
        host,ticket=self.prepare_hook()
        with self.assertRaises(ValueError):
            self.invoke('hook-start',host)
        ticket['verified_at']=int(time.time())-121
        P.replace(self.ticket,P.canonical(ticket))
        with self.assertRaises(ValueError):
            self.invoke('hook-start',host)
        host.run.assert_not_called()

    def test_cleanup_stops_owned_container_despite_partial_journal_update(self):
        host,_=self.prepare_hook()
        P.replace(self.root/'active.json',b'{"partial":true}\n')
        P.create(self.journal.directory/('backend-'+'c'*32+'.json'),P.canonical(dict(
            container_id='7'*64,approval_id='c'*32,invocation='d'*32,bundle=str(self.root))))
        host.runtime.container.side_effect=None
        host.runtime.container.return_value={'Id':'7'*64,'Image':self.plan['candidate_image_id'],
            'Name':'/hobbytree-backend-1','State':{'Running':True},
            'HostConfig':{'RestartPolicy':{'Name':'no'}},'Config':{'Image':host.runtime.image,
            'Labels':{'hobbytree.v24.request':self.plan['request'],'hobbytree.v24.nonce':'c'*32}}}
        self.invoke('hook-cleanup',host)
        self.assertEqual(host.run.call_args.args[0],['docker','container','stop','--time','40','7'*64])


class BundleTests(unittest.TestCase):
    def test_changed_catalogue_is_rejected(self):
        with self.assertRaises(ValueError):
            B.catalogue_jdbc(b'COMMIT; DELETE FROM users;')

    def test_known_wrapper_is_removed_and_settings_are_owned_by_jdbc(self):
        # The exact wrapper shape is separately hash-bound.
        raw=("-- review\nBEGIN;\nSELECT set_config('hobbytree.expected_database', :'target_database', true);\n"
             "SELECT set_config('hobbytree.approval_reference', :'approval_reference', true);\n"
             "LOCK TABLE public.hobbies,public.hobby_stages,public.activities,public.activity_steps IN SHARE ROW EXCLUSIVE MODE;\n"
             "SELECT 1;\n\\if :apply\n  COMMIT;\n\\else\n  ROLLBACK;\n\\endif\n").encode()
        with patch.object(B,'CATALOGUE_REVIEW_SHA',P.sha(raw)):
            converted=B.catalogue_jdbc(raw)
        self.assertNotIn(b'COMMIT',converted)
        self.assertNotIn(b'set_config',converted)
        self.assertTrue(converted.startswith(b'LOCK TABLE'))

    def test_jar_requires_cli_and_exact_24_scripts(self):
        with tempfile.TemporaryDirectory() as temp:
            jar=Path(temp)/'candidate.jar'
            with zipfile.ZipFile(jar,'w') as archive:
                archive.writestr('BOOT-INF/classes/com/hobbytree/hobbytree/deployment/V24MigrationCli.class',b'fixture')
                for n in range(1,25):
                    archive.writestr(f'BOOT-INF/classes/db/migration/V{n}__fixture.sql',b'SELECT 1;\r\n')
            history,fingerprints=B.jar_metadata(jar)
            self.assertEqual(len(history),24)
            self.assertNotEqual(fingerprints['18'],fingerprints['24'])
            with zipfile.ZipFile(jar,'a') as archive:
                archive.writestr('BOOT-INF/classes/db/migration/V25__unreviewed.sql',b'SELECT 1;')
            with self.assertRaises(ValueError):
                B.jar_metadata(jar)


if __name__=='__main__':
    unittest.main()
