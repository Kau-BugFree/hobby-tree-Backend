#!/usr/bin/env python3
"""Create/verify offline phase receipts. Private keys never enter the install bundle."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
import sys
import time


def require(ok):
    if not ok: raise ValueError('Approval verification failed')


def tool():
    here = Path(__file__).resolve()
    sys.dont_write_bytecode = True
    candidates = [here.parents[2] / 'scripts/deploy/schema-transition.py', here.parents[1] / 'payload/schema-transition.py']
    source = next(p for p in candidates if p.is_file())
    spec = importlib.util.spec_from_file_location('approval_transition', source)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def new_file(path, raw):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as out: out.write(raw); out.flush(); os.fsync(out.fileno())


def context(body, plan_raw, phase, now):
    t = tool(); plan = json.loads(plan_raw)
    require(re.fullmatch('[0-9]{1,20}-[0-9]{1,5}',plan['request']) is not None)
    t.validate_plan(plan, plan['request'])
    require(set(body) == {'format','approval_id','request','phase','plan_sha256','issued_at','expires_at'})
    require(body['format'] == 1 and re.fullmatch('[0-9a-f]{32}',body['approval_id']) is not None)
    require(body['request'] == plan['request'] and phase in t.APPROVALS and body['phase'] == phase)
    require(body['plan_sha256'] == hashlib.sha256(plan_raw).hexdigest())
    require(type(body['issued_at']) is int and type(body['expires_at']) is int)
    require(body['issued_at'] <= now and now + t.HOST.FORWARD_SECONDS <= body['expires_at'] <= plan['expires_at'])
    require(0 < body['expires_at'] - body['issued_at'] <= 7200)


def verify(body_file, signature, plan_file, phase, public, fingerprint):
    raw = body_file.read_bytes(); require(len(raw) <= 16384 and len(signature.read_bytes()) <= 16384)
    require(re.fullmatch('[0-9a-f]{64}',fingerprint) is not None and hashlib.sha256(public.read_bytes()).hexdigest() == fingerprint)
    context(json.loads(raw), plan_file.read_bytes(), phase, int(time.time()))
    result = subprocess.run(['openssl','dgst','-sha256','-verify',str(public),'-signature',str(signature),str(body_file)],capture_output=True,timeout=10)
    require(result.returncode == 0)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command',choices=('draft','sign','verify'))
    p.add_argument('--plan',type=Path,required=True)
    p.add_argument('--phase',required=True)
    p.add_argument('--output-directory',type=Path)
    p.add_argument('--body',type=Path)
    p.add_argument('--signature',type=Path)
    p.add_argument('--private-key',type=Path)
    p.add_argument('--public-key',type=Path)
    p.add_argument('--public-key-sha256')
    p.add_argument('--ttl-seconds',type=int,default=7200)
    a=p.parse_args()
    try:
        if a.command=='draft':
            now=int(time.time()); raw=a.plan.read_bytes(); plan=json.loads(raw)
            require(1200 <= a.ttl_seconds <= 7200 and a.output_directory.is_dir())
            proof=dict(format=1,approval_id=secrets.token_hex(16),request=plan['request'],phase=a.phase,
                       plan_sha256=hashlib.sha256(raw).hexdigest(),issued_at=now,expires_at=min(now+a.ttl_seconds,plan['expires_at']))
            context(proof,raw,a.phase,now)
            new_file(a.output_directory/(proof['approval_id']+'.json'),(json.dumps(proof,sort_keys=True,indent=2)+'\n').encode())
            print('DRAFT_APPROVAL_ID='+proof['approval_id'])
        elif a.command=='verify':
            verify(a.body,a.signature,a.plan,a.phase,a.public_key,a.public_key_sha256)
            print('PASS: offline approval signature/context/expiry verified; host still enforces one-time use.')
        else:
            # Signing must occur on a separate trusted Linux workstation, outside every Git checkout.
            require(os.name=='posix' and not a.private_key.is_symlink())
            info=a.private_key.stat(); require(stat.S_ISREG(info.st_mode) and info.st_nlink==1 and stat.S_IMODE(info.st_mode)==0o600 and info.st_uid==os.geteuid())
            require(b'ENCRYPTED PRIVATE KEY' in a.private_key.read_bytes())
            for parent in [a.private_key.resolve().parent,*a.private_key.resolve().parent.parents]: require(not (parent/'.git').exists())
            context(json.loads(a.body.read_bytes()),a.plan.read_bytes(),a.phase,int(time.time()))
            # Reserve final signature without overwriting any prior approval. No passphrase arguments/env.
            require(not a.signature.exists() and not a.signature.is_symlink())
            fd=os.open(a.signature,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
            os.close(fd)
            r=subprocess.run(['openssl','dgst','-sha256','-sign',str(a.private_key),'-out',str(a.signature),str(a.body)],timeout=300)
            require(r.returncode==0)
            verify(a.body,a.signature,a.plan,a.phase,a.public_key,a.public_key_sha256)
            print('PASS: phase receipt signed and independently verified.')
        return 0
    except BaseException:
        print('FAIL: approval preparation stopped; no keys or provider diagnostics emitted.',file=sys.stderr)
        return 1


if __name__=='__main__': sys.exit(main())
