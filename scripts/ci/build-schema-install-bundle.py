#!/usr/bin/env python3
"""Deterministic install-only archive from a clean, committed Git SHA. No AWS calls."""
import argparse
import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile

ROOT=Path(__file__).resolve().parents[2]


def load(name, path):
    spec=importlib.util.spec_from_file_location(name,path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


INSTALL=load('schema_install',ROOT/'scripts/deploy/schema-install.py')
RENDER=load('schema_document',ROOT/'scripts/ci/render-transition-document.py')


def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT)


def archive(revision, files):
    INSTALL.require(re.fullmatch('[0-9a-f]{40}',revision) is not None and set(files)==set(INSTALL.BUNDLE_FILES))
    scripts={name:files['payload/'+name] for name in ('ec2-release.py','schema-transition.py')}
    INSTALL.require(json.loads(files['document/HobbyTree-Dev-SchemaTransition.json'])==RENDER.render_document(scripts))
    manifest={'format':1,'revision':revision,'files':{}}
    for name,(source,destination,mode) in INSTALL.BUNDLE_FILES.items():
        manifest['files'][name]=dict(source=source,destination=destination,mode=mode,uid=0,gid=0,sha256=hashlib.sha256(files[name]).hexdigest())
    raw=(json.dumps(manifest,indent=2,sort_keys=True)+'\n').encode()
    contents={'manifest.json':raw,**files}
    result=io.BytesIO()
    with gzip.GzipFile(fileobj=result,mode='wb',filename='',mtime=0) as compressed:
        with tarfile.open(fileobj=compressed,mode='w',format=tarfile.USTAR_FORMAT) as output:
            for name in sorted(contents):
                entry=tarfile.TarInfo(name);entry.size=len(contents[name]);entry.uid=entry.gid=0;entry.mtime=0
                entry.mode=0o400 if name=='manifest.json' else int(manifest['files'][name]['mode'],8)
                output.addfile(entry,io.BytesIO(contents[name]))
    return result.getvalue(),raw,manifest


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--revision',required=True);p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    INSTALL.require(git('rev-parse','HEAD').decode().strip()==args.revision and not git('status','--porcelain').strip())
    files={name:git('show',args.revision+':'+source) for name,(source,_,_) in INSTALL.BUNDLE_FILES.items()}
    packed,raw,manifest=archive(args.revision,files)
    args.output.mkdir(parents=True,exist_ok=True)
    name='hobbytree-schema-install-'+args.revision+'.tar.gz'
    path=args.output/name
    INSTALL.require(not path.exists())
    path.write_bytes(packed)
    extractor=git('show',args.revision+':scripts/ci/extract-schema-install.py')
    (args.output/'extract-schema-install.py').write_bytes(extractor)
    receipt={'extractor_sha256':hashlib.sha256(extractor).hexdigest(),'revision':args.revision,'archive':name,'archive_sha256':hashlib.sha256(packed).hexdigest(),'manifest_sha256':hashlib.sha256(raw).hexdigest(),'files':manifest['files'],'contains_private_key':False,'contains_credentials':False,'contains_jar_or_image':False}
    (args.output/'install-bundle-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:receipt[k] for k in ('revision','archive','archive_sha256','manifest_sha256')}))


if __name__=='__main__':main()
