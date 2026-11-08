#!/usr/bin/env python3
"""Extract only a reviewed eight-file install archive into an empty root-owned staging directory."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import stat
import sys
import tarfile

NAMES={'manifest.json','payload/ec2-release.py','payload/schema-transition.py','tools/schema-install.py','tools/schema-phase-approval.py','document/HobbyTree-Dev-SchemaTransition.json','docs/installation.md','docs/transition.md'}


def require(ok):
    if not ok: raise ValueError('Archive validation failed')


def extract(archive, expected, destination):
    require(re.fullmatch('[0-9a-f]{64}',expected) is not None)
    require(not archive.is_symlink() and archive.is_file() and archive.stat().st_size < 2**20)
    data=archive.read_bytes();require(hashlib.sha256(data).hexdigest()==expected)
    require(destination.is_absolute() and destination.exists() and not any(destination.iterdir()))
    for p in [destination,*destination.parents]:
        info=p.lstat();require(stat.S_ISDIR(info.st_mode) and not p.is_symlink() and info.st_uid==0 and not info.st_mode & 0o022)
    import io
    with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as source:
        members=source.getmembers()
        require(len(members)==len(NAMES) and {m.name for m in members}==NAMES)
        require(all(m.isfile() and m.uid==m.gid==0 and m.mode in (0o400,0o500) and m.size < 2**20 for m in members))
        payloads={m.name:(source.extractfile(m).read(),m.mode) for m in members}
    for name,(raw,mode) in payloads.items():
        p=destination/name;p.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
        fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        with os.fdopen(fd,'wb') as out:
            out.write(raw);out.flush();os.fchown(out.fileno(),0,0);os.fchmod(out.fileno(),mode);os.fsync(out.fileno())


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--archive',type=Path,required=True);p.add_argument('--archive-sha256',required=True);p.add_argument('--destination',type=Path,required=True)
    a=p.parse_args()
    try:
        require(sys.platform=='linux' and os.geteuid()==0)
        extract(a.archive,a.archive_sha256,a.destination)
        print('PASS: reviewed archive extracted; no host installation or service/AWS actions.');return 0
    except BaseException:
        print('FAIL: extraction stopped; preserve staging for review; never overwrite or automatically delete it.',file=sys.stderr);return 1


if __name__=='__main__':sys.exit(main())
