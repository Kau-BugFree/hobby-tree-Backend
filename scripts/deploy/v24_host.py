"""Fixed EC2 adapter for the signed V24 protocol. Never called by ordinary CI/startup."""
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import time

from v24_protocol import HOST_FILES, TOOLS, canonical, create, protected, read, replace, require, sha, strict_json

ROOT = Path('/opt/hobbytree')
STATE = Path('/var/lib/hobbytree/deployments')
INSTALL = ROOT / 'scripts/rollout-v24'
HELPER = ROOT / 'scripts/deploy-schema/ec2-release.py'
HELPER_SHA = '87c56667002ac8bc85e90b9a11953f5a06c4845913f86f9b8d14610c35e81cbd'
OLD_GUARD = ROOT / 'scripts/reconcile-v18/v18-start-guard.py'
UNIT = 'hobbytree-backend.service'
UNIT_FILE = Path('/etc/systemd/system') / UNIT
OLD_DROPIN = UNIT_FILE.parent / (UNIT + '.d/90-v18-guard.conf')
DROPIN = OLD_DROPIN.with_name('95-v24-guard.conf')
TICKET = Path('/run/hobbytree-v24-ticket.json')
POINTER = Path('/etc/hobbytree/v24-active-request')
AI = Path('/etc/hobbytree/ai.properties')
PUBLIC_KEY = Path('/etc/hobbytree/transitions/approval-public.pem')
PUBLIC_SHA = '08f5c0df6bfbfadc4ec2a96b81e0274bcf46cb3df7f02f39cdd16622804e22a2'
COMPOSE_SHA = 'baf1ee80a7fec36cded7b275d2d9b527dfbc1289c103508bf04fccb101cdd8f0'
UNIT_SHA = '64c8402ff08cf1e3995459bf91c3546531365680e77b120ca44612772e2642ef'
SOCKET = Path('/run/docker.sock')


def load_release():
    require(sha(read(HELPER)) == HELPER_SHA)
    spec = importlib.util.spec_from_file_location('v24_fixed_release', HELPER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Host:
    def __init__(self, plan, bundle, journal, scratch, proof):
        self.plan, self.bundle, self.journal, self.scratch, self.proof = plan, Path(bundle), journal, Path(scratch), proof
        self.release = load_release()
        h = self.release
        require(plan['account'] == h.REGISTRY.split('.')[0] and plan['region'] == h.REGION
                and plan['instance'] == h.INSTANCE)
        require(read(Path('/var/lib/cloud/data/instance-id'), 128).decode().strip() == h.INSTANCE)
        self.commands = h.Commands(scratch)
        self.deadline = min(time.monotonic()+1200, time.monotonic()+proof['expires_at']-time.time()-30)
        self.runtime = h.Deployment(self.commands, scratch, plan['candidate'], plan['previous'], plan['revision'], plan['request'])
        self.runtime.deadline = self.deadline

    def run(self, argv, timeout=30, data=None):
        return self.commands.run(argv, self.deadline, timeout, data=data)

    def aws(self, *args, timeout=30):
        return strict_json(self.run(['aws', *args, '--region', self.plan['region'], '--output', 'json'], timeout))

    def bundle_check(self):
        for name, expected in self.plan['bundle'].items():
            require(sha(read(self.bundle/name)) == expected, 'Bundle changed')
        require(read(self.bundle/'history.tsv') == ('\n'.join(self.plan['history'])+'\n').encode())
        require(read(self.bundle/'database-policy.tsv') == ('\n'.join([
            self.plan['database_owner'], *sorted(self.plan['privileged_logins'])])+'\n').encode())

    def fixed_host(self, after=False):
        p = self.plan
        require(sha(read(ROOT/'compose.ec2.yml')) == COMPOSE_SHA and sha(read(UNIT_FILE)) == UNIT_SHA)
        require(sha(read(OLD_GUARD)) == p['legacy_guard_sha256'])
        require(sha(read(OLD_DROPIN)) == p['legacy_dropin_sha256'])
        for name, path in HOST_FILES.items():
            before = {'validate-ec2-config.py':'old_validator_sha256', 'fetch-ec2-secrets.py':'old_fetch_sha256',
                      'ec2-deployment-contract.json':'legacy_contract_sha256'}[name]
            require(sha(read(Path(path))) == (p['bundle'][name] if after else p[before]))
        if after:
            require(sha(read(DROPIN)) == p['bundle']['95-v24-guard.conf'])
            for name in TOOLS - {'95-v24-guard.conf'}:
                require(sha(read(INSTALL/name)) == p['bundle'][name])
        else:
            require(not os.path.lexists(DROPIN) and not os.path.lexists(POINTER))
            spec = importlib.util.spec_from_file_location('v24_legacy_guard', OLD_GUARD)
            guard = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(guard)
            guard.verify_unit(self.commands, self.deadline)

    def identity(self):
        require(self.aws('sts','get-caller-identity')['Account'] == self.plan['account'])

    def install_ai_only(self, values):
        # Preserve mounted DB/JWT/Redis credential inodes. Refreshing them while Redis
        # is running could silently install a rotated password that Redis has not loaded.
        spec = importlib.util.spec_from_file_location('v24_secret_files', ROOT/'scripts/fetch-ec2-secrets.py')
        helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(helper)
        helper.write_credentials(values['APP_SECRETS_DIR'],
            helper.read_ai_credentials(AI, values['APP_SECRETS_DIR']))

    def image_check(self):
        p, r = self.plan, self.runtime
        image = r.image_info(r.image)
        require(image['Id'] == p['candidate_image_id'] and r.image in image.get('RepoDigests', []))
        require((image['Config'].get('Labels') or {}).get('org.opencontainers.image.revision') == p['revision'])
        return image

    def compose(self, image, nonce):
        # Explicit override accounts for the live Swagger image not being written into legacy .env.
        override = self.scratch/'compose-v24.json'
        override.write_bytes(canonical({'services': {'backend': {'image':image, 'restart':'no',
            'pull_policy':'never', 'labels': {'hobbytree.v24.request':self.plan['request'], 'hobbytree.v24.nonce':nonce}}}}))
        args = ['docker','compose','--project-name','hobbytree','--project-directory',str(ROOT),
                '--env-file',str(self.release.ENV_FILE),'-f',str(ROOT/'compose.ec2.yml'),'-f',str(override)]
        model = strict_json(self.run(args+['config','--format','json']))
        require(model['name'] == 'hobbytree' and set(model['services']) == {'backend','redis'})
        backend = model['services']['backend']
        require(backend['image'] == image and backend['restart'] == 'no' and backend['pull_policy'] == 'never'
                and all(not backend.get(k) for k in ('build','entrypoint','command','post_start','pre_stop')))
        self.release.runtime_controls(backend['environment'])
        expected_url = ('jdbc:postgresql://'+self.plan['db_host']+
                        ':5432/hobbytree?sslmode=verify-full&sslrootcert=/etc/hobbytree/certs/rds-ca.pem')
        require(backend['environment']['DB_URL'] == expected_url)
        self.runtime.backend_config = backend
        return args, backend

    def same_redis(self, state):
        r = self.runtime.health('redis')
        require(r['Id'] == state['facts']['redis_id'] and r['Mounts'] == state['facts']['redis_mounts'])

    def target(self, verb):
        return self.aws('elbv2',verb,'--target-group-arn',self.release.TARGET_GROUP,
                        '--targets','Id='+self.plan['instance']+',Port=8080')

    def excluded(self):
        require(self.runtime.target_state() == 'unused', 'Backend must be excluded from ALB')

    def stopped(self, state):
        info = self.runtime.container('backend')
        require(info['Id'] == state['facts']['previous_container_id'] and not info['State']['Running']
                and info['Image'] == self.plan['previous_image_id'] and info['HostConfig']['RestartPolicy']['Name'] == 'no')
        values = self.run(['systemctl','show',UNIT,'--property=ActiveState,UnitFileState']).decode()
        require('ActiveState=inactive' in values and 'UnitFileState=disabled' in values)
        self.excluded()
        self.same_redis(state)

    def database(self, mode, state):
        self.bundle_check()
        source = Path('/run/hobbytree/migration-secrets')
        protected(source.parent, directory=True)
        require(not source.is_symlink() and source.is_dir() and source.stat().st_uid == 10001
                and stat.S_IMODE(source.stat().st_mode) == 0o500)
        cidfile=self.scratch/('db-'+mode+'.cid')
        require(not cidfile.exists())
        args=['docker','run','--rm','--pull','never','--platform','linux/amd64','--network','host',
              '--cidfile',str(cidfile),'--label','hobbytree.v24.request='+self.plan['request'],
              '--label','hobbytree.v24.db-mode='+mode,
              '--name','hobbytree-v24-db-'+self.plan['request'],'--read-only','--cap-drop','ALL',
              '--security-opt','no-new-privileges:true','--user','10001:10001','--memory','512m',
              '--pids-limit','128','--tmpfs','/tmp:rw,noexec,nosuid,size=64m','--entrypoint','java']
        for name in ('DB_USERNAME','DB_PASSWORD'):
            path=source/name
            require(not path.is_symlink() and path.is_file() and path.stat().st_uid == 10001
                    and stat.S_IMODE(path.stat().st_mode) == 0o400)
            args+=['--mount',f'type=bind,src={path},dst=/run/secrets/hobbytree/{name},readonly']
        require((source/'DB_USERNAME').read_text().strip() == self.plan['migration_user'])
        for name,target in [('history.tsv','/run/history.tsv'),('database-policy.tsv','/run/database-policy.tsv'),
                            ('catalogue.sql','/run/catalogue.sql')]:
            # Bundle directories stay root-only. A bind-mounted individual public
            # input must nevertheless be readable by the non-root JVM (UID 10001).
            staged=self.scratch/('db-'+name)
            staged.write_bytes(read(self.bundle/name))
            staged.chmod(0o444)
            args+=['--mount',f'type=bind,src={staged},dst={target},readonly']
        ca=ROOT/'certs/rds-ca.pem'
        protected(ca)
        args+=['--mount',f'type=bind,src={ca},dst=/etc/hobbytree/certs/rds-ca.pem,readonly']
        environment={'DB_URL':'jdbc:postgresql://'+self.plan['db_host']+
                     ':5432/hobbytree?sslmode=verify-full&sslrootcert=/etc/hobbytree/certs/rds-ca.pem',
                     'TRANSITION_DB_HOST':self.plan['db_host'],'TRANSITION_APP_USER':self.plan['app_user'],
                     'TRANSITION_MIGRATION_USER':self.plan['migration_user'],
                     'TRANSITION_REQUEST':self.plan['request'],'TRANSITION_CATALOGUE_SHA256':self.plan['bundle']['catalogue.sql']}
        if state['facts'].get('fence_sha256'):
            environment['TRANSITION_FENCE_SHA256']=state['facts']['fence_sha256']
        for key,value in environment.items():
            args+=['-e',key+'='+value]
        args += [self.runtime.image,'-Xmx256m','-Dloader.main=com.hobbytree.hobbytree.deployment.V24MigrationCli',
                 '-cp','/app/hobbytree.jar','org.springframework.boot.loader.launch.PropertiesLauncher',
                 mode,'/run/history.tsv','18','/run/database-policy.tsv']
        try:
            result=self.run(args,900)
        except BaseException:
            # Killing the docker CLI does not kill its container. Stop only this
            # exact CID/label/image; retain evidence and never retry migration.
            self.stop_database(cidfile,mode)
            raise
        require(result.strip().endswith(('TRANSITION_PASS='+mode).encode()))
        if mode in ('check-fence','check-activation'):
            values=re.findall(rb'(?m)^TRANSITION_FENCE_SHA256=([0-9a-f]{64})\r?$',result)
            require(len(values)==1)
            return values[0].decode()

    def stop_database(self, cidfile, mode):
        if not cidfile.exists():
            return
        cid=read(cidfile,128).decode().strip()
        require(re.fullmatch(r'[0-9a-f]{64}',cid))
        self.deadline=time.monotonic()+75
        # --rm can already have removed a failed process. Listing by exact ID
        # distinguishes absence from an unavailable Docker daemon.
        present=self.run(['docker','ps','-aq','--no-trunc','--filter','id='+cid]).decode().splitlines()
        if not present:
            return
        require(present==[cid])
        values=strict_json(self.run(['docker','inspect',cid]))
        require(len(values)==1)
        info=values[0]
        labels=info['Config'].get('Labels') or {}
        require(info['Id']==cid and info['Image']==self.plan['candidate_image_id']
                and info['Config']['Image']==self.runtime.image
                and labels.get('hobbytree.v24.request')==self.plan['request']
                and labels.get('hobbytree.v24.db-mode')==mode)
        if info['State']['Running']:
            self.run(['docker','stop','--time','20',cid],30)

    def check_fence(self, state, active=False):
        require(self.database('check-activation' if active else 'check-fence',state)==state['facts']['fence_sha256'])

    def check_ai(self):
        raw=read(AI,4096)
        require(stat.S_IMODE(AI.stat().st_mode)==0o600 and sha(raw)==self.plan['ai_properties_sha256'])
        scope={'__name__':'v24_ai_config','__file__':str(self.bundle/'validate-ec2-config.py')}
        exec(compile(read(self.bundle/'validate-ec2-config.py'),scope['__file__'],'exec'),scope)
        scope['validate_ai_properties'](raw.decode('ascii'))
        values=dict(line.split('=',1) for line in raw.decode('ascii').splitlines() if line and not line.startswith('#'))
        for prefix,port in (('recommendation',8000),('coach',8003)):
            require(values[prefix+'.ai.enabled']=='true' and
                    values[prefix+'.ai.base-url'].rstrip('/')==f'http://{self.plan["ai_private_ip"]}:{port}')
            self.run(['curl','--fail','--silent','--output','/dev/null','--max-time','5',
                      f'http://{self.plan["ai_private_ip"]}:{port}/health'])

    def perform(self, phase, state):
        self.bundle_check()
        self.identity()
        p,r=self.plan,self.runtime
        if phase=='prepare':
            self.fixed_host()
            require(sha(read(STATE/'active.json'))==p['legacy_active_sha256'])
            require(stat.S_IMODE(self.release.ENV_FILE.stat().st_mode)==0o600)
            original=read(self.release.ENV_FILE)
            self.run(['python3',str(ROOT/'scripts/validate-ec2-config.py'),'--env-file',str(self.release.ENV_FILE),'--check-secrets'])
            _,backend=self.compose(r.previous,p['request'])
            current=r.health('backend',p['previous_image_id'])
            require(current['Config']['Image']==r.previous and current['HostConfig']['RestartPolicy']['Name']=='no')
            actual_env=dict(x.split('=',1) for x in current['Config']['Env'])
            require(all(actual_env.get(k)==str(v) for k,v in backend['environment'].items()))
            r.verify_target_contract()
            require(r.target_state()=='healthy')
            redis=r.health('redis')
            self.image_check()
            require(r.extract_schema(r.previous,'previous')==p['before_schema'])
            require(r.extract_schema(r.image,'candidate')==p['after_schema'])
            require(sha((self.scratch/'candidate.jar').read_bytes())==p['candidate_jar_sha256'])
            self.database('check-before',state)
            create(self.journal.directory/'settings.before',original)
            return {'settings_sha256':sha(original),'previous_container_id':current['Id'],
                    'redis_id':redis['Id'],'redis_mounts':redis['Mounts']}
        require(read(self.release.ENV_FILE)==read(self.journal.directory/'settings.before')
                if phase in ('quiesce','verify-fence','backup','migrate','configure') else True)
        if phase=='quiesce':
            self.fixed_host()
            current=r.health('backend',p['previous_image_id'])
            require(current['Id']==state['facts']['previous_container_id'])
            self.run(['systemctl','disable',UNIT])
            self.run(['docker','update','--restart=no',current['Id']])
            self.target('deregister-targets')
            self.run(['aws','elbv2','wait','target-deregistered','--region',p['region'],
                      '--target-group-arn',self.release.TARGET_GROUP,'--targets','Id='+p['instance']+',Port=8080'],420)
            self.run(['systemctl','stop',UNIT],60)
            self.stopped(state)
            return {'stopped_at':datetime.now(timezone.utc).isoformat()}
        if phase in ('verify-fence','backup','migrate','configure'):
            self.fixed_host()
            self.stopped(state)
        if phase=='verify-fence':
            return {'fence_sha256':self.database('check-fence',state),
                    'fence_verified_at':datetime.now(timezone.utc).isoformat()}
        if phase=='backup':
            self.check_fence(state)
            db=self.aws('rds','describe-db-instances','--db-instance-identifier',p['rds'])['DBInstances']
            require(len(db)==1 and db[0]['DBInstanceStatus']=='available' and db[0]['StorageEncrypted']
                    and db[0]['Endpoint']['Address']==p['db_host'])
            self.aws('rds','create-db-snapshot','--db-instance-identifier',p['rds'],'--db-snapshot-identifier',p['snapshot'])
            self.run(['aws','rds','wait','db-snapshot-available','--region',p['region'],
                      '--db-snapshot-identifier',p['snapshot']],900)
            return {'snapshot_arn':self.snapshot(state)}
        if phase=='migrate':
            self.check_fence(state)
            require(self.snapshot(state)==state['facts']['snapshot_arn'])
            self.database('check-ready',state)
            self.database('migrate',state)
            self.database('check-after',state)
            self.database('validate-app',state)
            self.check_fence(state)
            return {'schema_version':24}
        if phase=='configure':
            self.check_fence(state)
            self.database('check-after',state)
            self.check_ai()
            if not INSTALL.exists():
                protected(INSTALL.parent,directory=True)
                INSTALL.mkdir(mode=0o700)
            protected(INSTALL,directory=True)
            require(not any(INSTALL.iterdir()),'V24 tool installation already exists')
            for name in TOOLS-{'95-v24-guard.conf'}:
                create(INSTALL/name,read(self.bundle/name),0o500)
            for name,target in HOST_FILES.items():
                create(self.journal.directory/(name+'.before'),read(Path(target)))
                replace(Path(target),read(self.bundle/name),0o600 if name.endswith('.json') else 0o644)
            settings=self.release.image_in_env(read(self.journal.directory/'settings.before'),r.image)
            replace(self.release.ENV_FILE,settings)
            scope={'__name__':'v24_config'}
            exec(compile(read(ROOT/'scripts/validate-ec2-config.py'),'validator','exec'),scope)
            values=scope['read_env_file'](self.release.ENV_FILE)
            self.install_ai_only(values)
            self.run(['python3',str(ROOT/'scripts/validate-ec2-config.py'),'--env-file',str(self.release.ENV_FILE),'--check-secrets'])
            create(DROPIN,read(self.bundle/'95-v24-guard.conf'),0o644)
            create(POINTER,(p['request']+'\n').encode())
            self.run(['systemctl','daemon-reload'])
            self.fixed_host(after=True)
            self.verify_unit()
            self.same_redis(state)
            return {'candidate_settings_sha256':sha(settings)}
        self.fixed_host(after=True)
        self.verify_unit()
        require(sha(read(self.release.ENV_FILE))==state['facts']['candidate_settings_sha256'])
        self.check_ai()
        self.image_check()
        if phase=='activate':
            self.stopped(state)
            self.check_fence(state,active=True)
            self.database('check-active-after',state)
            self.database('validate-active-app',state)
            ticket={'request':p['request'],'plan_sha256':self.journal.plan_hash,
                    'phase':phase,'approval_id':self.proof['approval_id'],'boot_id':Path('/proc/sys/kernel/random/boot_id').read_text().strip(),
                    'verified_at':int(time.time()),'bundle':str(self.bundle)}
            create(TICKET,canonical(ticket))
            self.run(['systemctl','start',UNIT],300)
            _,_=self.compose(r.image,self.proof['approval_id'])
            current=r.health('backend',p['candidate_image_id'])
            self.excluded()
            self.same_redis(state)
            return {'candidate_container_id':current['Id'],'active_approval_id':self.proof['approval_id']}
        if phase=='publish':
            self.compose(r.image,state['facts']['active_approval_id'])
            current=r.health('backend',p['candidate_image_id'])
            require(current['Id']==state['facts']['candidate_container_id'])
            self.excluded()
            self.same_redis(state)
            self.target('register-targets')
            r.redis={'Id':state['facts']['redis_id'],'Mounts':state['facts']['redis_mounts']}
            r.verify_running(p['candidate_image_id'])
            # Deliberately disabled/no restart: a later start needs its own reviewed ticket.
            return {'alb_stable_seconds':150}
        if phase=='catalogue':
            self.compose(r.image,state['facts']['active_approval_id'])
            require(r.health('backend',p['candidate_image_id'])['Id']==state['facts']['candidate_container_id'])
            require(r.target_state()=='healthy')
            self.database('catalogue',state)
            return {'catalogue_rows':3570,'catalogue_activities':386}
        if phase=='restart':
            self.compose(r.image,state['facts']['active_approval_id'])
            current=r.health('backend',p['candidate_image_id'])
            require(current['Id']==state['facts']['candidate_container_id'])
            self.target('deregister-targets')
            self.run(['aws','elbv2','wait','target-deregistered','--region',p['region'],
                      '--target-group-arn',self.release.TARGET_GROUP,'--targets','Id='+p['instance']+',Port=8080'],420)
            self.run(['systemctl','stop',UNIT],60)
            require(not r.container('backend')['State']['Running'])
            self.excluded()
            self.same_redis(state)
            self.check_fence(state,active=True)
            self.database('check-active-after',state)
            self.database('validate-active-app',state)
            create(TICKET,canonical({'request':p['request'],'plan_sha256':self.journal.plan_hash,
                'phase':phase,'approval_id':self.proof['approval_id'],
                'boot_id':Path('/proc/sys/kernel/random/boot_id').read_text().strip(),
                'verified_at':int(time.time()),'bundle':str(self.bundle)}))
            self.run(['systemctl','start',UNIT],300)
            self.compose(r.image,self.proof['approval_id'])
            current=r.health('backend',p['candidate_image_id'])
            self.same_redis(state)
            self.target('register-targets')
            r.redis={'Id':state['facts']['redis_id'],'Mounts':state['facts']['redis_mounts']}
            r.verify_running(p['candidate_image_id'])
            return {'candidate_container_id':current['Id'],'active_approval_id':self.proof['approval_id']}
        raise ValueError('Unsupported phase')

    def snapshot(self,state):
        p=self.plan
        rows=self.aws('rds','describe-db-snapshots','--db-snapshot-identifier',p['snapshot'])['DBSnapshots']
        require(len(rows)==1)
        value=rows[0]
        require(value['Status']=='available' and value['SnapshotType']=='manual' and value['Encrypted']
                and value['DBInstanceIdentifier']==p['rds']
                and value['DBSnapshotArn'].startswith(f'arn:aws:rds:{p["region"]}:{p["account"]}:snapshot:'))
        require(datetime.fromisoformat(value['SnapshotCreateTime'].replace('Z','+00:00')) >=
                datetime.fromisoformat(state['facts']['fence_verified_at']))
        return value['DBSnapshotArn']

    def verify_unit(self):
        arrays=('EnvironmentFiles','ExecCondition','ExecStartPre','ExecStartPost')
        keys='LoadState,Type,RemainAfterExit,Restart,FragmentPath,DropInPaths,Environment,ExecStart,ExecStop,ExecStopPost,ExecReload,NeedDaemonReload,WorkingDirectory,PassEnvironment,UnsetEnvironment,'+','.join(arrays)
        raw=self.run(['systemctl','show',UNIT,'--no-pager','--all','--property='+keys]).decode()
        values=dict(line.split('=',1) for line in raw.splitlines())
        expected={'LoadState':'loaded','Type':'oneshot','RemainAfterExit':'yes','Restart':'no',
                  'FragmentPath':str(UNIT_FILE),'DropInPaths':str(OLD_DROPIN)+' '+str(DROPIN),
                  'Environment':'','NeedDaemonReload':'no','ExecReload':'',
                  'WorkingDirectory':str(ROOT),'PassEnvironment':'','UnsetEnvironment':'',
                  **{key:'' for key in arrays}}
        for key in arrays:
            if key not in values:
                result = strict_json(self.run(['busctl','--system','--json=short','get-property',
                    'org.freedesktop.systemd1','/org/freedesktop/systemd1/unit/hobbytree_2dbackend_2eservice',
                    'org.freedesktop.systemd1.Service',key]))
                require(result == {'type':'a(sb)' if key=='EnvironmentFiles' else 'a(sasbttttuii)','data':[]})
                values[key]=''
        require(all(values.get(k)==v for k,v in expected.items()))
        for key,action in (('ExecStart','hook-start'),('ExecStop','hook-stop'),('ExecStopPost','hook-cleanup')):
            pattern=r'\{ path=/usr/bin/python3 ; argv\[\]=/usr/bin/python3 -B -I '+re.escape(str(INSTALL/'v24_transition.py'))+' '+action+r' ; ignore_errors=no ; [^{}]*\}'
            require(re.fullmatch(pattern,values[key]))

    def contain(self,state):
        results={}
        for name,action in (
            ('exclude',lambda:self.target('deregister-targets')),
            ('disable',lambda:self.run(['systemctl','disable',UNIT])),
            ('stop',lambda:self.run(['systemctl','stop',UNIT],60))):
            try:
                self.deadline=time.monotonic()+90
                action()
                results[name]=True
            except Exception:
                results[name]=False
        # No DB ACL changes, old-image rollback, Redis recreation or journal deletion.
        return results
