#!/usr/bin/env python3
"""Manual, separately signed phases; install only after review and user approval.

Ordinary Docker/CI workflows never invoke this file. No rollback to V18 exists.
"""
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import stat
import sys
import tempfile
import time


def load_local(name):
    # -I excludes both CWD and environment injection; load only adjacent protected code.
    path = Path(__file__).resolve().with_name(name + '.py')
    for part in (path, *path.parents):
        info = part.lstat()
        if info.st_uid != 0 or stat.S_ISLNK(info.st_mode) or info.st_mode & 0o022:
            raise ValueError('Unprotected transition code')
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def signature(host, body, signed):
    p, h = sys.modules['v24_protocol'], sys.modules['v24_host']
    p.require(p.sha(p.read(h.PUBLIC_KEY)) == h.PUBLIC_SHA)
    key, sig = host.scratch/'approval-public.pem', host.scratch/'approval.sig'
    key.write_bytes(p.read(h.PUBLIC_KEY))
    sig.write_bytes(signed)
    host.run(['openssl','dgst','-sha256','-verify',str(key),'-signature',str(sig)],10,data=body)


def owned(host, ledger):
    p = sys.modules['v24_protocol']
    info = host.runtime.container('backend')
    p.require(info['Id'] == ledger['container_id'] and info['Image'] == host.plan['candidate_image_id']
              and info['Config']['Image'] == host.runtime.image and info['Name'] == '/hobbytree-backend-1'
              and info['HostConfig']['RestartPolicy']['Name'] == 'no')
    labels = info['Config'].get('Labels') or {}
    p.require(labels.get('hobbytree.v24.request') == host.plan['request']
              and labels.get('hobbytree.v24.nonce') == ledger['approval_id'])
    return info


def hook(action, p, h):
    request = p.read(h.POINTER,128).decode().strip()
    p.require(p.ID.fullmatch(request))
    directory = h.STATE/('v24-'+request)
    plan_raw = p.read(directory/'plan.json')
    plan = p.validate_plan(p.strict_json(plan_raw))
    journal = p.Journal(h.STATE,request,p.sha(plan_raw))
    invocation = os.environ.get('INVOCATION_ID','')
    p.require(p.ID.fullmatch(invocation))
    if action != 'hook-start':
        # Stops need no new start ticket. A stale invocation cannot touch a replacement.
        entries = list(directory.glob('backend-*.json'))
        for path in entries:
            ledger = p.strict_json(p.read(path))
            if ledger['invocation'] != invocation:
                continue
            proof = dict(expires_at=int(time.time())+120)
            with tempfile.TemporaryDirectory(prefix='v24-stop-',dir='/run') as scratch:
                host = h.Host(plan,ledger['bundle'],journal,scratch,proof)
                info = owned(host,ledger)
                if info['State']['Running']:
                    host.run(['docker','container','stop','--time','40',info['Id']],50)
            return
        return
    state = journal.load()
    ticket = p.strict_json(p.read(h.TICKET,4096))
    p.require(ticket['request']==request and ticket['plan_sha256']==journal.plan_hash
              and ticket['phase'] in ('activate','restart')
              and state['phase']==ticket['phase']+'-running'
              and ticket['boot_id']==Path('/proc/sys/kernel/random/boot_id').read_text().strip()
              and 0 <= time.time()-ticket['verified_at'] <= 120)
    nonce, phase = ticket['approval_id'], ticket['phase']
    p.require(p.ID.fullmatch(nonce) and state['approvals'][phase]==nonce)
    body=p.read(directory/(phase+'-'+nonce+'.approval.json'),16384)
    signed=p.read(directory/(phase+'-'+nonce+'.approval.sig'),16384)
    proof=p.validate_approval(body,journal.plan_hash,request,phase)
    marker=p.strict_json(p.read(h.STATE/('v24-approval-'+nonce+'.used')))
    p.require(marker==dict(request=request,phase=phase,plan_sha256=journal.plan_hash,approval_sha256=p.sha(body)))
    # Controller remains the only owner of the shared deployment lock during systemd start.
    with open(p.protected(h.STATE/'lock'),'rb') as lock:
        try:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:
            pass
        else:
            raise ValueError('Controller lock is not held')
    with tempfile.TemporaryDirectory(prefix='v24-start-',dir='/run') as scratch:
        host=h.Host(plan,ticket['bundle'],journal,scratch,proof)
        host.deadline=min(host.deadline,time.monotonic()+240)
        host.runtime.deadline=host.deadline
        signature(host,body,signed)
        host.bundle_check()
        host.fixed_host(after=True)
        host.verify_unit()
        host.check_ai()
        image=host.image_check()
        host.excluded()
        host.same_redis(state)
        p.require(p.sha(p.read(host.release.ENV_FILE))==state['facts']['candidate_settings_sha256'])
        previous=host.runtime.container('backend')
        old_id = state['facts']['candidate_container_id'] if phase=='restart' else state['facts']['previous_container_id']
        old_image = plan['candidate_image_id'] if phase=='restart' else plan['previous_image_id']
        p.require(previous['Id']==old_id and previous['Image']==old_image and not previous['State']['Running']
                  and previous['HostConfig']['RestartPolicy']['Name']=='no')
        # Burn the boot-bound ticket before any container create/start. It is never regenerated on failure.
        h.TICKET.unlink()
        p.sync(h.TICKET.parent)
        args,model=host.compose(host.runtime.image,nonce)
        host.run(args+['up','--no-start','--no-deps','--no-build','--pull','never','--force-recreate','backend'],60)
        info=host.runtime.container('backend')
        ledger={'container_id':info['Id'],'approval_id':nonce,'invocation':invocation,'bundle':ticket['bundle']}
        p.require(re.fullmatch(r'[0-9a-f]{64}',info['Id']))
        p.create(directory/('backend-'+nonce+'.json'),p.canonical(ledger))
        expected_env=dict(item.split('=',1) for item in image['Config'].get('Env',[]))
        expected_env.update({k:str(v) for k,v in model['environment'].items()})
        info=owned(host,ledger)
        host.runtime.backend_runtime(info)
        config=info['HostConfig']
        p.require(info['State']['Status']=='created' and not info['State']['Running']
                  and dict(item.split('=',1) for item in info['Config']['Env'])==expected_env
                  and set(config.get('CapDrop') or [])=={'ALL'} and not config.get('CapAdd')
                  and set(config.get('SecurityOpt') or []) in ({'no-new-privileges'},{'no-new-privileges:true'}))
        p.require(journal.load()==state)
        host.verify_unit()
        host.run(['docker','container','start',info['Id']])
        while time.monotonic() < host.deadline:
            info=owned(host,ledger)
            p.require(info['State']['Running'])
            if info['State'].get('Health',{}).get('Status')=='healthy':
                host.same_redis(state)
                return
            time.sleep(2)
        raise TimeoutError('Candidate health timeout')


def main():
    if os.geteuid()!=0:
        raise ValueError('Linux root required')
    p=load_local('v24_protocol')
    h=load_local('v24_host')
    if len(sys.argv)==2 and sys.argv[1] in ('hook-start','hook-stop','hook-cleanup'):
        return hook(sys.argv[1],p,h)
    p.require(len(sys.argv)==5, 'phase plan-path approval-path signature-path required')
    phase=sys.argv[1]
    p.require(phase in p.PHASES)
    plan_path=Path(sys.argv[2])
    plan_raw=p.read(plan_path,65536)
    plan=p.validate_plan(p.strict_json(plan_raw))
    body=p.read(Path(sys.argv[3]),16384)
    signed=p.read(Path(sys.argv[4]),16384)
    proof=p.validate_approval(body,p.sha(plan_raw),plan['request'],phase)
    bundle=plan_path.parent
    journal=p.Journal(h.STATE,plan['request'],p.sha(plan_raw))
    with tempfile.TemporaryDirectory(prefix='v24-phase-',dir='/run') as scratch:
        host=h.Host(plan,bundle,journal,scratch,proof)
        signature(host,body,signed)
        host.bundle_check()
        # Bind the script actually executing, not only an unused adjacent bundle copy.
        for name in ('v24_transition.py','v24_protocol.py','v24_host.py'):
            p.require(p.sha(p.read(Path(__file__).resolve().with_name(name)))==plan['bundle'][name])
        with open(p.protected(h.STATE/'lock'),'rb') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            if phase=='prepare':
                state=journal.begin(plan,plan_raw)
            else:
                state=journal.load()
                p.require(p.read(journal.directory/'plan.json')==plan_raw)
            if phase=='quiesce':
                p.require(p.sha(p.read(h.STATE/'active.json'))==plan['legacy_active_sha256'])
            journal.authorize_once(state,proof,body,signed)
            p.execute_phase(journal,state,proof,host)
    print(json.dumps({'request':plan['request'],'phase':phase,'result':'PASS'}))


def interrupt(*_):
    raise InterruptedError('Phase interrupted')


if __name__=='__main__':
    signal.signal(signal.SIGTERM,interrupt)
    signal.signal(signal.SIGINT,interrupt)
    try:
        main()
    except BaseException:
        print('V24 transition failed: retain evidence; no automatic image/schema rollback.',file=sys.stderr)
        sys.exit(1)
