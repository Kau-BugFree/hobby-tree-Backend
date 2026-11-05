#!/usr/bin/env python3
"""Verify the one approved V5 artifact; publish only via the protected manual job."""

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('release_schema', ROOT / 'scripts/deploy/ec2-release.py')
SCHEMA = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCHEMA)
MAX_ZIP = 250 * 1024 * 1024


class VerificationError(Exception):
    """Only fixed diagnostics may be printed; never HTTP/subprocess payloads."""


def require(ok, message):
    if not ok:
        raise VerificationError(message)


def lock():
    return json.loads((ROOT / 'deploy/v5-artifact-lock.json').read_text())


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def api(path, expected):
    """Read only fixed-repository GitHub API paths; never forward auth redirects."""
    url = 'https://api.github.com/repos/' + expected['repository'] + '/' + path
    headers = {'Accept': 'application/vnd.github+json', 'User-Agent': 'hobbytree-v5-artifact'}
    token = os.environ.get('GH_TOKEN')
    if token:
        headers['Authorization'] = 'Bearer ' + token
    return build_opener(NoRedirect()).open(Request(url, headers=headers), timeout=60)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def api_json(path, expected):
    with api(path, expected) as response:
        return json.load(response)


def validate_metadata(run, artifact, jobs, expected, now=None):
    """Pin attempt, source workflow, repository, successful job and immutable ZIP."""
    fields = {'id': expected['run_id'], 'run_attempt': expected['run_attempt'],
              'head_sha': expected['workflow_sha'], 'workflow_id': expected['workflow_id'],
              'path': expected['workflow_path'], 'event': 'workflow_dispatch',
              'status': 'completed', 'conclusion': 'success'}
    require(all(run.get(k) == v for k, v in fields.items()), 'Unapproved or unsuccessful source run.')
    require(run.get('repository', {}).get('id') == expected['repository_id'] and
            run.get('head_repository', {}).get('id') == expected['repository_id'], 'Wrong source repository.')
    require(any(j.get('id') == expected['job_id'] and j.get('status') == 'completed' and
                j.get('conclusion') == 'success' for j in jobs.get('jobs', [])), 'Candidate job did not succeed.')
    fields = {'id': expected['artifact_id'], 'name': expected['artifact_name'],
              'size_in_bytes': expected['artifact_size'], 'digest': 'sha256:' + expected['zip_sha256']}
    require(all(artifact.get(k) == v for k, v in fields.items()) and artifact.get('expired') is False,
            'Missing, expired or unapproved artifact.')
    provenance = artifact.get('workflow_run', {})
    require(all(provenance.get(k) == v for k, v in {
        'id': expected['run_id'], 'head_sha': expected['workflow_sha'],
        'repository_id': expected['repository_id'], 'head_repository_id': expected['repository_id']}.items()),
        'Artifact provenance mismatch.')
    expires = datetime.fromisoformat(artifact['expires_at'].replace('Z', '+00:00'))
    require(expires > (now or datetime.now(timezone.utc)), 'Artifact expired.')


def download(directory, expected):
    """Download exactly the original ZIP; no fallback to another run or artifact."""
    run = api_json(f"actions/runs/{expected['run_id']}", expected)
    artifact = api_json(f"actions/artifacts/{expected['artifact_id']}", expected)
    jobs = api_json(f"actions/runs/{expected['run_id']}/attempts/{expected['run_attempt']}/jobs?per_page=100", expected)
    validate_metadata(run, artifact, jobs, expected)
    require(not directory.exists(), 'Download directory must be new.')
    directory.mkdir(parents=True, mode=0o700)
    try:
        api(f"actions/artifacts/{expected['artifact_id']}/zip", expected)
        raise VerificationError('Expected a GitHub artifact download redirect.')
    except HTTPError as response:
        require(response.code == 302, 'Artifact download unavailable.')
        url = response.headers['Location']
    parsed = urlsplit(url)
    require(parsed.scheme == 'https' and parsed.hostname and not parsed.username and not parsed.password and
            (parsed.hostname.endswith('.blob.core.windows.net') or
             parsed.hostname.endswith('.actions.githubusercontent.com')), 'Untrusted artifact download host.')
    # This second request has NO Authorization header. Signed URLs are never logged.
    with build_opener(NoRedirect()).open(Request(url), timeout=120) as response, (directory / 'artifact.zip').open('xb') as output:
        total = 0
        while block := response.read(1024 * 1024):
            total += len(block)
            require(total <= MAX_ZIP, 'Artifact ZIP exceeds limit.')
            output.write(block)
    extract(directory, expected)
    verify_files(directory, expected)


def extract(directory, expected):
    require(sha(directory / 'artifact.zip') == expected['zip_sha256'], 'Artifact ZIP SHA256 mismatch.')
    with zipfile.ZipFile(directory / 'artifact.zip') as archive:
        files = archive.infolist()
        names = [x.filename for x in files]
        require(sorted(names) == sorted(['evidence.json', 'SHA256SUMS', expected['archive_name']]),
                'Unexpected, duplicate or missing artifact members.')
        require(sum(x.file_size for x in files) <= 350 * 1024 * 1024, 'Expanded artifact exceeds limit.')
        for entry in files:
            mode = entry.external_attr >> 16
            require(not mode or stat.S_IFMT(mode) in (0, stat.S_IFREG), 'Nonregular artifact member.')
            with archive.open(entry) as source, (directory / entry.filename).open('xb') as target:
                shutil.copyfileobj(source, target)


def verify_files(directory, expected):
    require(sha(directory / 'artifact.zip') == expected['zip_sha256'], 'Artifact ZIP SHA256 mismatch.')
    require(sha(directory / expected['archive_name']) == expected['archive_sha256'], 'Image archive SHA256 mismatch.')
    require((directory / 'SHA256SUMS').read_text() ==
            expected['archive_sha256'] + '  ' + expected['archive_name'] + '\n', 'Archive checksum record mismatch.')
    evidence = json.loads((directory / 'evidence.json').read_text())
    fields = {k: expected[k] for k in ('source_sha', 'workflow_sha', 'source_tree', 'image_id', 'platform',
                                      'sql_files', 'sql_set_sha256', 'archive_sha256')}
    fields.update(oci_revision=expected['source_sha'], run_id=str(expected['run_id']),
                  run_attempt=str(expected['run_attempt']), runtime_validation='passed',
                  stable_health_seconds=30, backend_restarts=0, redis_preserved=True,
                  registry_push_performed=False, registry_manifest_digest=None)
    require(evidence == fields, 'Original validation evidence mismatch.')


def command(arguments, **kwargs):
    """No shell interpolation; discard all foreign stderr on failure."""
    return subprocess.run(arguments, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=kwargs.pop('timeout', 120), **kwargs).stdout


def verify_inspect(images, expected):
    require(len(images) == 1, 'Expected exactly one image.')
    image = images[0]
    require(image.get('Id') == expected['image_id'], 'Loaded image ID mismatch.')
    require(image.get('Os') + '/' + image.get('Architecture') == expected['platform'], 'Image platform mismatch.')
    require(image.get('Config', {}).get('Labels', {}).get('org.opencontainers.image.revision') == expected['source_sha'],
            'OCI Revision mismatch.')
    require(image.get('Config', {}).get('User') == '10001:10001', 'Image user mismatch.')


def verify_jar(path, expected):
    prefix = 'BOOT-INF/classes/db/migration/'
    with zipfile.ZipFile(path) as jar:
        names = sorted(x.filename[len(prefix):] for x in jar.infolist()
                       if x.filename.startswith(prefix) and not x.is_dir())
    require(names == expected['sql_files'], 'Packaged SQL list mismatch (V6 forbidden).')
    # Reuse the existing bounded deployment parser, including line-ending normalization.
    require(SCHEMA.schema_fingerprint(path) == expected['sql_set_sha256'], 'Packaged SQL checksum mismatch.')


def verify_image(directory, expected):
    verify_files(directory, expected)
    command(['docker', 'image', 'load', '--input', str(directory / expected['archive_name'])], timeout=180)
    verify_inspect(json.loads(command(['docker', 'image', 'inspect', expected['image_id']])), expected)
    container = command(['docker', 'create', '--network', 'none', '--entrypoint', '/bin/true', expected['image_id']]).decode().strip()
    require(bool(re.fullmatch('[0-9a-f]{64}', container)), 'Invalid inspection container ID.')
    try:
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'candidate.jar'
            command(['docker', 'cp', container + ':/app/hobbytree.jar', str(jar)])
            verify_jar(jar, expected)
    finally:
        command(['docker', 'rm', container])


def validate_environment(environment, policies, expected):
    require(environment.get('name') == expected['environment'], 'Wrong publication environment.')
    rules = environment.get('protection_rules', [])
    require(any(r.get('type') == 'required_reviewers' and r.get('reviewers') and
                r.get('prevent_self_review') is True for r in rules), 'Manual independent approval required.')
    require(environment.get('can_admins_bypass') is False, 'Environment admin bypass must be disabled.')
    require(environment.get('deployment_branch_policy') == {'protected_branches': False, 'custom_branch_policies': True},
            'Selected develop branch policy required.')
    require(policies.get('total_count') == 1 and
            [(p.get('name'), p.get('type')) for p in policies.get('branch_policies', [])] == [('develop', 'branch')],
            'Only develop branch may publish (tags forbidden).')


def check_environment(expected):
    base = 'environments/' + expected['environment']
    validate_oidc(api_json('actions/oidc/customization/sub', expected), expected)
    validate_environment(api_json(base, expected), api_json(base + '/deployment-branch-policies?per_page=100', expected), expected)


def validate_oidc(settings, expected):
    require(settings.get('use_default') is True and settings.get('use_immutable_subject') is True and
            settings.get('sub_claim_prefix') == expected['oidc_sub_prefix'], 'OIDC immutable subject configuration drift.')


def validate_repository(response, expected):
    repositories = response.get('repositories', [])
    require(len(repositories) == 1, 'ECR repository unavailable.')
    repo = repositories[0]
    require(repo.get('registryId') == expected['account_id'] and repo.get('repositoryName') == expected['ecr_repository'],
            'Wrong ECR repository.')
    require(repo.get('imageTagMutability') == 'IMMUTABLE' and not repo.get('imageTagMutabilityExclusionFilters'),
            'ECR must be IMMUTABLE without exclusions; no automatic policy changes.')


def require_absent(response, expected):
    failures = response.get('failures', [])
    require(not response.get('images') and len(failures) == 1 and
            failures[0].get('failureCode') == 'ImageNotFound' and
            failures[0].get('imageId') == {'imageTag': expected['source_sha']},
            'Tag exists or absence unproven; repeat publication forbidden.')


def validate_manifest(response, expected):
    require(not response.get('failures') and len(response.get('images', [])) == 1, 'Published manifest unavailable.')
    image = response['images'][0]
    require(image.get('registryId') == expected['account_id'] and image.get('repositoryName') == expected['ecr_repository'] and
            image.get('imageId', {}).get('imageTag') == expected['source_sha'], 'Published target mismatch.')
    raw = image['imageManifest']
    digest = 'sha256:' + hashlib.sha256(raw.encode()).hexdigest()
    require(image['imageId'].get('imageDigest') == digest, 'Registry manifest SHA256 mismatch.')
    manifest = json.loads(raw)
    require(manifest.get('schemaVersion') == 2 and manifest.get('config', {}).get('digest') == expected['image_id'],
            'Registry config digest differs from validated image ID.')
    require(manifest.get('mediaType') in ('application/vnd.docker.distribution.manifest.v2+json',
                                         'application/vnd.oci.image.manifest.v1+json'), 'Single-platform manifest required.')
    return digest


def aws(operation, expected, *arguments):
    repository_option = '--repository-names' if operation == 'describe-repositories' else '--repository-name'
    return command(['aws', 'ecr', operation, '--region', expected['region'], '--registry-id', expected['account_id'],
                    repository_option, expected['ecr_repository'], '--output', 'json', *arguments])


def publish(expected):
    """AWS-using entry point. Never called by tests/CI; no builds, deletion or deployment."""
    validate_repository(json.loads(aws('describe-repositories', expected)), expected)
    require_absent(json.loads(aws('batch-get-image', expected, '--image-ids', 'imageTag=' + expected['source_sha'])), expected)
    registry = expected['account_id'] + '.dkr.ecr.' + expected['region'] + '.amazonaws.com'
    uri = registry + '/' + expected['ecr_repository'] + ':' + expected['source_sha']
    password = command(['aws', 'ecr', 'get-login-password', '--region', expected['region']])
    command(['docker', 'login', '--username', 'AWS', '--password-stdin', registry], input=password)
    # Immutable ECR enforcement closes races with other publishers, not just this workflow's concurrency.
    command(['docker', 'image', 'tag', expected['image_id'], uri])
    command(['docker', 'image', 'push', '--platform', 'linux/amd64', uri], timeout=600)
    response = json.loads(aws('batch-get-image', expected, '--image-ids', 'imageTag=' + expected['source_sha']))
    digest = validate_manifest(response, expected)
    record = {'source_sha': expected['source_sha'], 'source_run_id': expected['run_id'],
              'source_attempt': expected['run_attempt'], 'source_artifact_id': expected['artifact_id'],
              'source_workflow_sha': expected['workflow_sha'], 'platform': expected['platform'],
              'oci_revision': expected['source_sha'], 'sql_set_sha256': expected['sql_set_sha256'],
              'publication_workflow_sha': os.environ.get('GITHUB_SHA'),
              'publication_run_id': os.environ.get('GITHUB_RUN_ID'),
              'publication_attempt': os.environ.get('GITHUB_RUN_ATTEMPT'),
              'archive_sha256': expected['archive_sha256'], 'image_id': expected['image_id'],
              'registry_manifest_digest': digest, 'image': uri, 'pinned_image': uri.rsplit(':', 1)[0] + '@' + digest}
    for key, value in record.items():
        print(f'{key}: {value}')
    Path(os.environ['RUNNER_TEMP'], 'v5-publication.json').write_text(json.dumps(record, indent=2) + '\n')
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write('## V5 artifact publication\n\n```json\n' + json.dumps(record, indent=2) + '\n```\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('download', 'verify', 'environment', 'publish'))
    parser.add_argument('--directory', type=Path)
    args = parser.parse_args()
    expected = lock()
    if args.action == 'download':
        require(args.directory is not None, 'Directory required.')
        download(args.directory, expected)
    elif args.action == 'verify':
        require(args.directory is not None, 'Directory required.')
        verify_image(args.directory, expected)
        print('PASS: original archive, image ID, OCI Revision, linux/amd64 and V1-V5 SQL; no AWS.')
    elif args.action == 'environment':
        check_environment(expected)
    else:
        require(os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' and
                os.environ.get('GITHUB_REPOSITORY') == expected['repository'] and
                os.environ.get('GITHUB_REF') == 'refs/heads/develop' and
                os.environ.get('V5_ARTIFACT_PUBLISH_ENABLED') == 'true', 'Unapproved publication context.')
        # Recheck bytes and local image at the credential-bearing boundary, without executing the image.
        verify_files(args.directory, expected)
        verify_inspect(json.loads(command(['docker', 'image', 'inspect', expected['image_id']])), expected)
        publish(expected)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('FAIL: ' + (str(error) if isinstance(error, VerificationError) else type(error).__name__))
        raise SystemExit(1) from None
