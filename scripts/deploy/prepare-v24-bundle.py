#!/usr/bin/env python3
"""Offline review bundle. Never signs approvals, contacts AWS, or opens a database."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile
import zlib

CATALOGUE_REVIEW_SHA = '335ca87e431744a967a3e6e3c63ebb00f627032360c8d4b4c2fae0a9d2e075fd'
CATALOGUE_SOURCE_SHA = '6a8efd0cbd34b0aded780b860870cfa3d60f12611767b1dc4df6e7e743cb1135'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def require(condition):
    if not condition:
        raise ValueError('Review bundle input mismatch')


def catalogue_jdbc(raw):
    # Only the already reviewed, insert-only transaction is accepted. This is not
    # a general SQL converter: changed input requires a new content review/pin.
    require(sha(raw) == CATALOGUE_REVIEW_SHA)
    sql = raw.decode('utf-8').replace('\r\n', '\n')
    trailer = "\\if :apply\n  COMMIT;\n\\else\n  ROLLBACK;\n\\endif\n"
    require(sql.endswith(trailer) and sql.count('\nBEGIN;\n') == 1)
    body = sql.split('\nBEGIN;\n', 1)[1][:-len(trailer)]
    for field in ('expected_database', 'approval_reference'):
        parameter = 'target_database' if field == 'expected_database' else field
        line = f"SELECT set_config('hobbytree.{field}', :'{parameter}', true);\n"
        require(body.count(line) == 1)
        body = body.replace(line, '')
    require(not re.search(r'(?im)^\s*(?:\\|COMMIT\b|ROLLBACK\b|BEGIN;)', body))
    require("LOCK TABLE public.hobbies,public.hobby_stages,public.activities," in body)
    return body.encode('utf-8')


def jar_metadata(path):
    prefix = 'BOOT-INF/classes/db/migration/'
    history, schemas = [], {}
    with zipfile.ZipFile(path) as jar:
        require('BOOT-INF/classes/com/hobbytree/hobbytree/deployment/V24MigrationCli.class' in jar.namelist())
        names = [n for n in jar.namelist() if n.startswith(prefix) and n.endswith('.sql')]
        require(len(names) == 24)
        for name in names:
            filename = name[len(prefix):]
            match = re.fullmatch(r'V([1-9][0-9]*)__[A-Za-z0-9_]+\.sql', filename)
            require(match is not None)
            content = jar.read(name).replace(b'\r\n', b'\n').replace(b'\r', b'\n')
            schemas[int(match[1])] = (filename, content)
        require(set(schemas) == set(range(1, 25)))
        for number in range(1, 25):
            filename, content = schemas[number]
            crc = 0
            for line in content.decode('utf-8-sig').split('\n'):
                crc = zlib.crc32(line.encode('utf-8'), crc)
            if crc >= 2**31:
                crc -= 2**32
            history.append(f'{number}\t{filename}\t{crc}')
    fingerprints = {}
    for limit in (18, 24):
        digest = hashlib.sha256()
        for filename, content in sorted(schemas[n] for n in range(1, limit+1)):
            digest.update(filename.encode()+b'\0'+hashlib.sha256(content).digest())
        fingerprints[str(limit)] = digest.hexdigest()
    return history, fingerprints


def prepare(repository, jar, catalogue, output, revision):
    require(re.fullmatch(r'[0-9a-f]{40}', revision) is not None)
    require(not output.exists())
    history, fingerprints = jar_metadata(jar)
    body = catalogue_jdbc(catalogue.read_bytes())
    files = {name:(repository/'scripts/deploy'/name).read_bytes() for name in
             ('v24_transition.py','v24_protocol.py','v24_host.py')}
    files['95-v24-guard.conf'] = (repository/'deploy/v24/95-v24-guard.conf').read_bytes()
    for name in ('validate-ec2-config.py','fetch-ec2-secrets.py'):
        files[name] = (repository/'scripts'/name).read_bytes()
    files['ec2-deployment-contract.json'] = (repository/'deploy/ec2-deployment-contract.json').read_bytes()
    files['history.tsv'] = ('\n'.join(history)+'\n').encode()
    files['catalogue.sql'] = body
    output.mkdir(parents=True)
    for name, raw in files.items():
        (output/name).write_bytes(raw)
    review = dict(revision=revision, candidate_jar_sha256=sha(jar.read_bytes()),
        before_schema=fingerprints['18'], after_schema=fingerprints['24'],
        catalogue_source_sha256=CATALOGUE_SOURCE_SHA, history=history,
        bundle={name:sha(raw) for name,raw in files.items()}, production_authorized=False,
        pending_bindings=['verified ECR digest and image ID / OCI revision',
            'current protected host and DB owner/privileged role inventory',
            'database-policy.tsv from reviewed inventory',
            'AI properties hash computed on target without displaying credentials',
            'new request/plan hash and separate signatures after user approval'])
    (output/'bundle-manifest.review.json').write_text(json.dumps(review, indent=2)+'\n', encoding='utf-8')
    return review


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--catalogue', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--revision', required=True)
    args = parser.parse_args()
    result = prepare(args.repository, args.jar, args.catalogue, args.output, args.revision)
    print(json.dumps({'review_bundle':str(args.output), 'files':len(result['bundle']),
                      'production_authorized':False}))
