"""Root Linux fixtures only: no host mounts, network, AWS, Docker or service calls."""
from contextlib import redirect_stderr, redirect_stdout
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]


def load(name,path):
    spec=importlib.util.spec_from_file_location(name,ROOT/path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module


B=load('install_builder','scripts/ci/build-schema-install-bundle.py')
I=B.INSTALL
E=load('safe_extract','scripts/ci/extract-schema-install.py')
A=load('phase_approval','scripts/ci/schema-phase-approval.py')
T=load('test_transition_for_install','scripts/deploy/schema-transition.py')


@unittest.skipUnless(sys.platform=='linux' and os.geteuid()==0,'root disposable Linux filesystem required')
class SchemaInstallTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='schema-install-test-',dir='/run');self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.fs=self.root/'host';self.fs.mkdir(mode=0o700);self.host=I.Host(self.fs)
        for path in ('opt/hobbytree/scripts','etc/hobbytree','run/hobbytree','var/lib/hobbytree/deployments'):
            p=self.fs/path;p.mkdir(parents=True,mode=0o755)
        for p in self.fs.rglob('*'):
            if p.is_dir():p.chmod(0o755)
        self.active=self.fs/'var/lib/hobbytree/deployments/active.json';self.active.write_text('{"status":"succeeded"}');self.active.chmod(0o600)
        lock=self.active.with_name('lock');lock.touch();lock.chmod(0o600)
        self.canary=self.fs/'opt/hobbytree/compose.ec2.yml';self.canary.write_bytes(b'legacy backend and redis');self.canary.chmod(0o600)
        self.files={name:(ROOT/source).read_bytes() for name,(source,_,_) in I.BUNDLE_FILES.items()}
        packed,self.raw,self.manifest=B.archive('a'*40,self.files)
        self.archive=self.root/'reviewed.tar.gz';self.archive.write_bytes(packed)
        self.stage=self.root/'stage';self.stage.mkdir(mode=0o700)
        E.extract(self.archive,hashlib.sha256(packed).hexdigest(),self.stage)
        self.key=self.root/'public.pem'
        subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(self.root/'test-private.pem')],check=True,capture_output=True)
        subprocess.run(['openssl','pkey','-in',str(self.root/'test-private.pem'),'-pubout','-out',str(self.key)],check=True,capture_output=True)
        self.key_raw=self.key.read_bytes();self.key.chmod(0o600)

    def install(self):
        with self.host.lock():self.host.install(self.stage,self.raw,self.manifest,self.key_raw)

    def plan(self):
        now=int(time.time())
        return dict(format=3,request='123-1',account='494915081306',region='ap-northeast-2',instance=T.HOST.INSTANCE,rds='hobbytree-dev-private',revision='a'*40,
                    candidate='sha256:'+'a'*64,previous='sha256:'+'b'*64,before_schema='c'*64,after_schema='d'*64,
                    host_helper_sha256=self.manifest['files']['payload/ec2-release.py']['sha256'],test_report_sha256='e'*64,jar_sha256='f'*64,expires_at=now+7000,
                    db_host='hobbytree.example.ap-northeast-2.rds.amazonaws.com',app_user='hobbytree_app',migration_user='hobbytree_migration',database_owner='postgres',
                    privileged_login_roles=['postgres'],acl_control='external-operator',guard_policy='retire-after-snapshot',snapshot='hobbytree-v5-test',writer_fence_policy='database-connect-acl',baseline=1,
                    history=['1\tV1__init.sql\t1','2\tV2__next.sql\t2'])

    def test_install_cli_requires_ack_and_private_inputs_require_protected_modes(self):
        argv=['installer','install','--bundle',str(self.stage),'--manifest-sha256',hashlib.sha256(self.raw).hexdigest(),'--public-key',str(self.key),'--public-key-sha256',hashlib.sha256(self.key_raw).hexdigest()]
        with patch.object(I,'Host',return_value=self.host),patch.object(sys,'argv',argv),redirect_stdout(io.StringIO()),redirect_stderr(io.StringIO()):self.assertEqual(I.main(),1)
        self.assertFalse(self.host.path(I.BASE).exists())
        secret=self.root/'input-secret';secret.write_bytes(b'ephemeral-fixture');secret.chmod(0o644)
        with self.assertRaises(ValueError):I.private_input(secret)
        secret.chmod(0o600);self.assertEqual(I.private_input(secret),b'ephemeral-fixture')
        link=self.root/'input-link';link.symlink_to(secret)
        with self.assertRaises(ValueError):I.private_input(link)

    def test_archive_is_deterministic_and_contains_only_reviewed_public_files(self):
        a,raw,m=B.archive('a'*40,self.files);b,_,_=B.archive('a'*40,self.files)
        self.assertEqual(a,b);self.assertEqual(set(m['files'])|{'manifest.json'},E.NAMES)
        directory,_,_=I.bundle(self.stage,hashlib.sha256(raw).hexdigest());self.assertEqual(directory,self.stage)
        changed=dict(self.files);doc=json.loads(changed['document/HobbyTree-Dev-SchemaTransition.json']);doc['parameters']['Phase']['allowedPattern']='.*'
        changed['document/HobbyTree-Dev-SchemaTransition.json']=json.dumps(doc).encode()
        with self.assertRaises(ValueError):B.archive('a'*40,changed)

    def test_preflight_is_read_only_and_install_is_idempotent_without_legacy_changes(self):
        before={str(p):p.read_bytes() for p in self.fs.rglob('*') if p.is_file()}
        with self.host.lock():self.host.check(self.stage,self.raw,self.manifest,self.key_raw)
        self.assertEqual(before,{str(p):p.read_bytes() for p in self.fs.rglob('*') if p.is_file()})
        self.install();self.install()
        self.host.check(self.stage,self.raw,self.manifest,self.key_raw,complete=True)
        for name,raw in before.items():self.assertEqual(Path(name).read_bytes(),raw)
        for name,entry in self.manifest['files'].items():
            if entry['destination']:
                p=self.host.path(entry['destination']);self.assertEqual(stat.S_IMODE(p.stat().st_mode),0o500)

    def test_credentials_require_migration_role_and_never_overwrite_or_emit_values(self):
        self.install()
        with self.assertRaises(ValueError):self.host.credentials(b'hobbytree_app',b'fixture-password')
        self.host.credentials(b'hobbytree_migration',b'fixture-password');self.host.verify_credentials()
        self.host.credentials(b'hobbytree_migration',b'fixture-password')
        with self.assertRaises(ValueError):self.host.credentials(b'hobbytree_migration',b'changed-password')
        for name in ('DB_USERNAME','DB_PASSWORD'):
            info=self.host.path(I.SECRETS+'/'+name).stat();self.assertEqual((info.st_uid,info.st_gid,stat.S_IMODE(info.st_mode)),(10001,10001,0o400))

    def test_file_drift_wrong_mode_symlink_and_hardlink_are_rejected(self):
        self.install();p=self.host.path(I.BASE+'/schema-transition.py');original=p.read_bytes()
        for scenario in ('bytes','mode','symlink','hardlink'):
            p.unlink()
            if scenario=='symlink':p.symlink_to(self.canary)
            elif scenario=='hardlink':os.link(self.canary,p)
            else:p.write_bytes(b'drift' if scenario=='bytes' else original);p.chmod(0o700 if scenario=='mode' else 0o500)
            with self.subTest(scenario=scenario),self.assertRaises(ValueError):self.install()
        self.assertEqual(self.canary.read_bytes(),b'legacy backend and redis')

    def test_bundle_rejects_tampering_unapproved_files_and_manifest_destination(self):
        self.assertEqual(I.bundle(self.stage,hashlib.sha256(self.raw).hexdigest())[2],self.manifest)
        p=self.stage/'payload/schema-transition.py';p.chmod(0o600);p.write_bytes(b'changed');p.chmod(0o500)
        with self.assertRaises(ValueError):I.bundle(self.stage,hashlib.sha256(self.raw).hexdigest())
        p.chmod(0o600);p.write_bytes(self.files['payload/schema-transition.py']);p.chmod(0o500)
        (self.stage/'secret.json').write_text('{}')
        with self.assertRaises(ValueError):I.bundle(self.stage,hashlib.sha256(self.raw).hexdigest())
        (self.stage/'secret.json').unlink()
        m=json.loads(self.raw);m['files']['payload/schema-transition.py']['destination']='/opt/hobbytree/compose.ec2.yml'
        manifest=self.stage/'manifest.json';manifest.chmod(0o600);raw=json.dumps(m).encode();manifest.write_bytes(raw);manifest.chmod(0o400)
        with self.assertRaises(ValueError):I.bundle(self.stage,hashlib.sha256(raw).hexdigest())

    def test_nonterminal_or_busy_shared_lock_blocks_install(self):
        self.active.write_text('{"status":"db-changing"}')
        with self.assertRaises(ValueError):self.install()
        self.assertFalse(self.host.path(I.BASE).exists())
        self.active.write_text('{"status":"succeeded"}')
        with self.host.lock():
            with self.assertRaises(BlockingIOError):self.install()
        self.assertFalse(self.host.path(I.BASE).exists())

    def test_partial_new_install_stops_then_identical_bundle_can_resume(self):
        original=self.host.write;calls=[]
        def write(*args,**kw):
            calls.append(args[0])
            if len(calls)==2:raise OSError('injected disk failure')
            return original(*args,**kw)
        with patch.object(self.host,'write',side_effect=write),self.assertRaises(OSError):self.install()
        with self.assertRaises(ValueError):self.host.check(self.stage,self.raw,self.manifest,self.key_raw,complete=True)
        self.assertEqual(self.canary.read_bytes(),b'legacy backend and redis')
        self.install();self.host.check(self.stage,self.raw,self.manifest,self.key_raw,complete=True)

    def test_extractor_rejects_wrong_hash_nonempty_and_archive_links_before_writes(self):
        empty=self.root/'extract-again';empty.mkdir(mode=0o700)
        with self.assertRaises(ValueError):E.extract(self.archive,'0'*64,empty)
        self.assertEqual(list(empty.iterdir()),[])
        with self.assertRaises(ValueError):E.extract(self.archive,hashlib.sha256(self.archive.read_bytes()).hexdigest(),self.stage)
        target=self.root/'bad.tar.gz'
        with tarfile.open(target,'w:gz') as out:
            for name in E.NAMES:
                entry=tarfile.TarInfo(name);entry.mode=0o400
                if name=='manifest.json':entry.type=tarfile.SYMTYPE;entry.linkname='/etc/passwd'
                out.addfile(entry,io.BytesIO(b''))
        with self.assertRaises(ValueError):E.extract(target,hashlib.sha256(target.read_bytes()).hexdigest(),empty)
        self.assertEqual(list(empty.iterdir()),[])

    def test_phase_receipts_match_runtime_and_stage_after_quiesce_without_journal_writes(self):
        self.install();plan_raw=json.dumps(self.plan()).encode();self.host.write(I.CONFIG+'/123-1.json',plan_raw)
        for index,phase in enumerate(T.APPROVALS):
            now=int(time.time());approval=f'{index+1:032x}'
            proof=dict(format=1,approval_id=approval,request='123-1',phase=phase,plan_sha256=hashlib.sha256(plan_raw).hexdigest(),issued_at=now,expires_at=now+5000)
            body=self.root/(approval+'.json');body.write_text(json.dumps(proof));sig=self.root/(approval+'.sig')
            subprocess.run(['openssl','dgst','-sha256','-sign',str(self.root/'test-private.pem'),'-out',str(sig),str(body)],check=True,capture_output=True)
            state={'status':'succeeded'} if phase=='quiesce' else dict(status=T.EXPECTED_STATE[phase],request='123-1',plan_sha256=proof['plan_sha256'])
            self.active.write_text(json.dumps(state));prior=self.active.read_bytes()
            argv=['install','stage-approval','--bundle',str(self.stage),'--manifest-sha256',hashlib.sha256(self.raw).hexdigest(),'--public-key',str(self.key),'--public-key-sha256',hashlib.sha256(self.key_raw).hexdigest(),'--input',str(body),'--signature',str(sig),'--ack','install-offline-signed-phase-approval']
            with patch.object(I,'Host',return_value=self.host),patch.object(sys,'argv',argv),redirect_stdout(io.StringIO()),redirect_stderr(io.StringIO()):self.assertEqual(I.main(),0)
            self.assertEqual(self.active.read_bytes(),prior)
            self.assertTrue(self.host.path(I.CONFIG+'/approvals/'+approval+'.sig').exists())
            self.host.path(I.STATE+'/schema-approval-'+approval+'.used').touch()
            with patch.object(I,'Host',return_value=self.host),patch.object(sys,'argv',argv),redirect_stdout(io.StringIO()),redirect_stderr(io.StringIO()):self.assertEqual(I.main(),1)

    def test_stage_plan_binds_source_revision_and_never_replaces_existing_plan(self):
        self.install();p=self.root/'reviewed-plan.json';raw=json.dumps(self.plan()).encode();p.write_bytes(raw)
        argv=['installer','stage-plan','--bundle',str(self.stage),'--manifest-sha256',hashlib.sha256(self.raw).hexdigest(),'--public-key',str(self.key),'--public-key-sha256',hashlib.sha256(self.key_raw).hexdigest(),'--input',str(p),'--ack','install-reviewed-transition-plan']
        with patch.object(I,'Host',return_value=self.host),patch.object(sys,'argv',argv),redirect_stdout(io.StringIO()),redirect_stderr(io.StringIO()):self.assertEqual(I.main(),0)
        for key,value in [('revision','b'*40),('snapshot','hobbytree-v5-different'),('request','approvals/forged')]:
            changed=json.loads(raw);changed[key]=value;p.write_text(json.dumps(changed))
            with patch.object(I,'Host',return_value=self.host),patch.object(sys,'argv',argv),redirect_stdout(io.StringIO()),redirect_stderr(io.StringIO()):self.assertEqual(I.main(),1)
            self.assertEqual(self.host.path(I.CONFIG+'/123-1.json').read_bytes(),raw)

    def test_encrypted_signer_cli_draft_verify_and_reuse_rejection(self):
        private=self.root/'encrypted-private.pem';public=self.root/'encrypted-public.pem'
        password=b'ephemeral-fixture-passphrase\n'
        subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-aes-256-cbc','-pass','stdin','-out',str(private)],input=password,check=True,capture_output=True)
        subprocess.run(['openssl','pkey','-in',str(private),'-passin','stdin','-pubout','-out',str(public)],input=password,check=True,capture_output=True)
        private.chmod(0o600);plan=self.root/'sign-plan.json';plan.write_text(json.dumps(self.plan()))
        cli=[sys.executable,'-B',str(ROOT/'scripts/ci/schema-phase-approval.py')]
        result=subprocess.run([*cli,'draft','--plan',str(plan),'--phase','quiesce','--output-directory',str(self.root)],capture_output=True,check=True)
        approval=result.stdout.decode().strip().split('=')[1];body=self.root/(approval+'.json');sig=self.root/(approval+'.sig')
        args=['--plan',str(plan),'--phase','quiesce','--body',str(body),'--signature',str(sig),'--public-key',str(public),'--public-key-sha256',hashlib.sha256(public.read_bytes()).hexdigest()]
        result=subprocess.run([*cli,'sign',*args,'--private-key',str(private)],input=password,capture_output=True)
        self.assertEqual(result.returncode,0,result.stderr.decode());self.assertNotIn(password.strip(),result.stdout+result.stderr)
        subprocess.run([*cli,'verify',*args],check=True,capture_output=True)
        original=sig.read_bytes()
        result=subprocess.run([*cli,'sign',*args,'--private-key',str(private)],input=password,capture_output=True)
        self.assertEqual(result.returncode,1);self.assertEqual(sig.read_bytes(),original)

    def test_offline_approval_verifier_rejects_context_expiry_and_signature_tampering(self):
        raw=json.dumps(self.plan()).encode();now=int(time.time())
        proof=dict(format=1,approval_id='a'*32,request='123-1',phase='quiesce',plan_sha256=hashlib.sha256(raw).hexdigest(),issued_at=now,expires_at=now+5000)
        A.context(proof,raw,'quiesce',now)
        for key,value in [('phase','deploy'),('expires_at',now+1199),('issued_at',now+1),('plan_sha256','b'*64),('extra','x')]:
            bad=dict(proof);bad[key]=value
            with self.subTest(key=key),self.assertRaises((ValueError,T.HOST.ReleaseError)):A.context(bad,raw,'quiesce',now)
        plan=self.root/'plan.json';plan.write_bytes(raw);body=self.root/'receipt.json';body.write_text(json.dumps(proof));sig=self.root/'receipt.sig'
        subprocess.run(['openssl','dgst','-sha256','-sign',str(self.root/'test-private.pem'),'-out',str(sig),str(body)],check=True,capture_output=True)
        fp=hashlib.sha256(self.key_raw).hexdigest();A.verify(body,sig,plan,'quiesce',self.key,fp)
        body.write_bytes(body.read_bytes()+b' ')
        with self.assertRaises(ValueError):A.verify(body,sig,plan,'quiesce',self.key,fp)


if __name__=='__main__':unittest.main()
