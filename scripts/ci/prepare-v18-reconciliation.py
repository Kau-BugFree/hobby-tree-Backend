#!/usr/bin/env python3
"""Offline UNSIGNED reconciliation draft. Never reads a private key or calls AWS."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import sys
import time

def require(ok):
    if not ok:raise ValueError('Recovery draft validation failed')


def create(source_raw, target_raw, source_journal_sha256, now):
    source=json.loads(source_raw);target=json.loads(target_raw)
    require(hashlib.sha256(source_raw).hexdigest()=='4866957adc987b9d58a99d6f35c6a76e049334571b79f43a3fde57cceed3eab7')
    require(re.fullmatch('[0-9a-f]{64}',source_journal_sha256) is not None)
    require(set(target)==set(source) and all(target[k]==source[k] for k in source if k not in ('request','expires_at')))
    require(re.fullmatch('[0-9]{1,20}-[0-9]{1,5}',target.get('request','')) is not None and target['request']!=source['request'])
    require(type(target['expires_at']) is int and now+1200<=target['expires_at']<=now+7200)
    return dict(format=1,action='reconcile-v18-db-verified',source_request=source['request'],
        source_plan_sha256=hashlib.sha256(source_raw).hexdigest(),source_journal_sha256=source_journal_sha256,
        target_request=target['request'],target_plan_sha256=hashlib.sha256(target_raw).hexdigest(),
        approval_id=secrets.token_hex(16),issued_at=now,expires_at=target['expires_at'])


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source-plan',type=Path,required=True)
    p.add_argument('--plan',type=Path,required=True)
    p.add_argument('--source-journal-sha256',required=True)
    p.add_argument('--output-directory',type=Path,required=True)
    a=p.parse_args()
    try:
        require(a.output_directory.is_dir())
        proof=create(a.source_plan.read_bytes(),a.plan.read_bytes(),a.source_journal_sha256,int(time.time()))
        path=a.output_directory/(proof['approval_id']+'.json')
        fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
        with os.fdopen(fd,'wb') as f:
            f.write((json.dumps(proof,sort_keys=True,indent=2)+'\n').encode());f.flush();os.fsync(f.fileno())
        print('DRAFT_RECONCILIATION_ID='+proof['approval_id'])
        return 0
    except BaseException:
        print('FAIL: unsigned recovery draft not completed; no provider/key diagnostics emitted.',file=sys.stderr)
        return 1


if __name__=='__main__':sys.exit(main())
