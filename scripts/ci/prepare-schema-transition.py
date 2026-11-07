#!/usr/bin/env python3
"""Offline rehearsal evidence and approval-plan preparation. No AWS mutation APIs."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('host', ROOT / 'scripts/deploy/ec2-release.py')
HOST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST)
SQL = 'src/main/resources/db/migration/'


def require(ok, message):
    if not ok: raise ValueError(message)


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, check=True, capture_output=True, timeout=30).stdout


def inventory(files):
    result = []
    for name, raw in files:
        match = re.fullmatch(r'V([1-9][0-9]*)__[A-Za-z0-9_]+\.sql', name)
        require(match is not None, 'Unsupported SQL path')
        checksum = zlib.crc32(''.join(raw.decode('utf-8-sig').splitlines()).encode())
        result.append({'version': int(match[1]), 'script': name, 'sha256': hashlib.sha256(raw).hexdigest(),
                       'checksum': checksum if checksum < 2**31 else checksum - 2**32})
    result.sort(key=lambda row: row['version'])
    require([row['version'] for row in result] == list(range(1, len(result) + 1)) and result, 'SQL versions must be contiguous')
    return result


def git_inventory(sha):
    require(re.fullmatch(r'[0-9a-f]{40}', sha) is not None, 'Full Git SHA required')
    paths = git('ls-tree', '-r', '--name-only', sha, '--', SQL).decode().splitlines()
    return inventory((p.removeprefix(SQL), git('show', sha + ':' + p)) for p in paths)


def history_rows(entries):
    return [f"{e['version']}\t{e['script']}\t{e['checksum']}" for e in entries]


def schema_hash(entries, sha):
    digest = hashlib.sha256()
    for e in sorted(entries, key=lambda row: row['script']):
        raw = git('show', sha + ':' + SQL + e['script']).replace(b'\r\n', b'\n').replace(b'\r', b'\n')
        digest.update(e['script'].encode() + b'\0' + hashlib.sha256(raw).digest())
    return digest.hexdigest()


def evidence(revision, baseline_sha, history, jar):
    require(git('rev-parse', 'HEAD').decode().strip() == revision, 'Checkout changed; rerun tests for this SHA')
    require(not git('status', '--porcelain', '--', 'src', 'Dockerfile', 'build.gradle', 'settings.gradle', 'gradle'), 'Commit executable source before sealing evidence')
    baseline, candidate = git_inventory(baseline_sha), git_inventory(revision)
    require(candidate[:len(baseline)] == baseline and len(candidate) > len(baseline), 'Applied baseline SQL changed or missing upgrade')
    expected = history_rows(candidate)
    require(history.read_text(encoding='utf-8').splitlines() == expected, 'Actual Flyway rehearsal history mismatch')
    with zipfile.ZipFile(jar) as archive:
        prefix = 'BOOT-INF/classes/db/migration/'
        packaged = inventory((n.removeprefix(prefix), archive.read(n)) for n in archive.namelist() if n.startswith(prefix) and not n.endswith('/'))
        require(packaged == candidate, 'Packaged JAR SQL does not match Git bytes')
        require('BOOT-INF/classes/com/hobbytree/hobbytree/deployment/SchemaMigrationCli.class' in archive.namelist(), 'Standalone migration tool missing from image JAR')
    return {'format': 1, 'revision': revision, 'baseline_revision': baseline_sha, 'baseline': len(baseline),
            'target': len(candidate), 'history': expected, 'migrations': candidate,
            'before_schema': schema_hash(baseline, baseline_sha), 'after_schema': HOST.schema_fingerprint(jar),
            'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'schema_changed': True}


def plan(proof, args):
    require(args.candidate != args.previous, 'Candidate must differ from previous image')
    for digest in (args.candidate, args.previous):
        require(re.fullmatch(r'sha256:[0-9a-f]{64}', digest) is not None, 'Manifest digests required; image IDs are insufficient')
    return {k: proof[k] for k in ('revision', 'baseline', 'history', 'before_schema', 'after_schema', 'jar_sha256')} | {
        'format': 2,
        'request': args.request, 'account': '494915081306', 'region': 'ap-northeast-2', 'instance': HOST.INSTANCE,
        'rds': 'hobbytree-dev-private', 'candidate': args.candidate, 'previous': args.previous,
        'host_helper_sha256': hashlib.sha256((ROOT / 'scripts/deploy/ec2-release.py').read_bytes()).hexdigest(),
        'test_report_sha256': hashlib.sha256(args.test_report.read_bytes()).hexdigest(),
        'expires_at': int(time.time()) + 7200, 'db_host': args.db_host, 'app_user': args.app_user,
        'migration_user': args.migration_user, 'snapshot': args.snapshot, 'writer_fence_policy': 'database-connect-acl'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--baseline-sha', required=True)
    parser.add_argument('--history', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--request')
    for key in ('candidate', 'previous', 'db-host', 'app-user', 'migration-user', 'snapshot'):
        parser.add_argument('--' + key)
    parser.add_argument('--test-report', type=Path)
    args = parser.parse_args()
    proof = evidence(args.revision, args.baseline_sha, args.history, args.jar)
    if args.request:
        require(all(getattr(args, key) for key in ('candidate', 'previous', 'db_host', 'app_user', 'migration_user', 'snapshot', 'test_report')), 'Complete post-publication plan inputs required')
        report = json.loads(args.test_report.read_text())
        require(report.get('revision') == args.revision and report.get('source_tree') == git('rev-parse', 'HEAD^{tree}').decode().strip(), 'Tests belong to a different Git tree/SHA')
        require(report.get('tests', 0) > 0 and all(report.get(k) == 0 for k in ('failures', 'errors', 'skipped')), 'Passing non-skipped tests required')
        proof = plan(proof, args)
        spec = importlib.util.spec_from_file_location('transition', ROOT / 'scripts/deploy/schema-transition.py')
        transition = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(transition)
        transition.validate_plan(proof, args.request)
    args.output.write_text(json.dumps(proof, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    print('Prepared public evidence SHA256=' + hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == '__main__': main()
