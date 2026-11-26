"""Main-only approval and SSM transport; never mutate repository protection or AWS policy."""
import base64
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError, URLError


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


HERE = Path(__file__).parent
RELEASE = load('manifest', HERE / 'release-manifest.py')
API = load('ssm_api', HERE / 'ec2-preflight.py')
HOST = load('main_host', HERE.parent / 'deploy/main_release_host.py')
def require(ok, message="Main release invariant failed"):
    RELEASE.require(ok, message)
ENVIRONMENT = 'hobbytree-main-production'
CURRENT_STAGE = 'entry'


def stage(name):
    """Only call with static labels; record progress before a potentially failing operation."""
    global CURRENT_STAGE
    CURRENT_STAGE = name
    print('MAIN_RELEASE_STAGE=' + name, flush=True)


def failure_diagnostic(error):
    """Emit bounded metadata only, never exception text, URLs, headers, bodies or tracebacks."""
    record = {'stage': CURRENT_STAGE, 'kind': 'unexpected'}
    if isinstance(error, HTTPError):
        record['kind'] = 'github_http'
        if type(error.code) is int and 100 <= error.code <= 599:
            record['http_status'] = error.code
    elif isinstance(error, URLError):
        record['kind'] = 'network'
    elif isinstance(error, API.PreflightError):
        record['kind'] = 'ssm'
        code = error.diagnostic_code
        record['code'] = code if code in API.DIAGNOSTIC_CODES else 'SSM_REQUEST_FAILED'
    elif isinstance(error, RELEASE.PUBLISH.VerificationError):
        record['kind'] = 'invariant'
    elif isinstance(error, json.JSONDecodeError):
        record['kind'] = 'invalid_json'
    elif isinstance(error, KeyError):
        record['kind'] = 'missing_field'
    elif isinstance(error, TimeoutError):
        record['kind'] = 'timeout'
    elif isinstance(error, OSError):
        record['kind'] = 'io'
    elif isinstance(error, (TypeError, ValueError)):
        record['kind'] = 'invalid_value'
    return record


def provenance(sha):
    """Main Merge Commit from develop or a frozen, merged develop source; never arbitrary heads."""
    pub = RELEASE.PUBLISH
    target = RELEASE.TARGET
    stage('provenance_commit')
    commit = pub.api_json('commits/' + sha, target)
    require(len(commit.get('parents', [])) == 2, 'A reviewed main Merge Commit is required.')
    stage('provenance_main_pr')
    prs = pub.api_json('commits/' + sha + '/pulls?per_page=100', target)
    matched = [pr for pr in prs if pr.get('merged_at') and pr.get('merge_commit_sha') == sha
               and pr['base']['ref'] == 'main'
               and (pr['head']['ref'] == 'develop'
                    or re.fullmatch(r'deploy/[1-9][0-9]{0,19}-[a-z0-9]+(?:-[a-z0-9]+)*', pr['head']['ref']))
               and pr['head']['repo']['full_name'] == RELEASE.REPOSITORY
               and pr['head']['sha'] == commit['parents'][1]['sha']]
    require(len(matched) == 1, 'Main release must originate in one reviewed repository PR.')
    head = commit['parents'][1]['sha']
    if matched[0]['head']['ref'] != 'develop':
        # A branch-name convention alone never authorizes new/unreviewed code.
        stage('provenance_develop_pr')
        sources = pub.api_json('commits/' + head + '/pulls?per_page=100', target)
        merged = [pr for pr in sources if pr.get('merged_at') and pr.get('merge_commit_sha')
                  and pr.get('base', {}).get('ref') == 'develop'
                  and pr.get('base', {}).get('repo', {}).get('full_name') == RELEASE.REPOSITORY
                  and pr.get('head', {}).get('repo', {}).get('full_name') == RELEASE.REPOSITORY
                  and head in (pr['merge_commit_sha'], pr['head']['sha'])]
        require(len(merged) == 1, 'Frozen head must be an exact head or merge of a merged develop PR.')
        stage('provenance_lineage')
        lineage = pub.api_json('compare/' + head + '...develop', target)
        require(lineage.get('status') in ('ahead', 'identical')
                and lineage.get('base_commit', {}).get('sha') == head
                and lineage.get('merge_base_commit', {}).get('sha') == head,
                'Frozen source is not contained in current develop.')
    stage('provenance_checks')
    checks = pub.api_json('commits/' + head + '/check-runs?filter=latest&per_page=100', target)
    needed = {'Spotless Check', 'Docker amd64 and PostgreSQL 17', 'Flyway PostgreSQL 17 migrations'}
    passed = {c['name'] for c in checks.get('check_runs', []) if c.get('conclusion') == 'success'
              and c.get('head_sha') == head and c.get('app', {}).get('slug') == 'github-actions'}
    require(needed <= passed, 'Release head integration checks have not all succeeded on its exact head.')
    return head


def protection(environment, branches):
    require(environment.get('name') == ENVIRONMENT and environment.get('can_admins_bypass') is False,
            'Production Environment must exist without administrator bypass.')
    reviewers = [r for r in environment.get('protection_rules', []) if r.get('type') == 'required_reviewers']
    require(len(reviewers) == 1 and reviewers[0].get('reviewers') and
            all(r.get('type') == 'User' and r.get('reviewer', {}).get('login') == 'rladnqls14'
                for r in reviewers[0]['reviewers']), 'Named operator approval required.')
    require(environment.get('deployment_branch_policy') == {'protected_branches': False, 'custom_branch_policies': True})
    require(branches.get('total_count') == 1 and
            [(b.get('name'), b.get('type')) for b in branches.get('branch_policies', [])] == [('main', 'branch')],
            'Only the main branch may deploy; tags and develop are forbidden.')


def approved():
    stage('release_context')
    RELEASE.context()
    stage('release_enabled')
    require(os.environ.get('HOBBYTREE_MAIN_RELEASE_ENABLED') == 'true', 'Production release is disabled.')
    pub, target = RELEASE.PUBLISH, RELEASE.TARGET
    stage('environment_read')
    environment = pub.api_json('environments/' + ENVIRONMENT, target)
    stage('environment_branches')
    branches = pub.api_json('environments/' + ENVIRONMENT + '/deployment-branch-policies?per_page=100', target)
    stage('environment_protection')
    protection(environment, branches)
    stage('oidc')
    pub.validate_oidc(pub.api_json('actions/oidc/customization/sub', target), target)
    stage('latest_main')
    require(pub.api_json('git/ref/heads/main', target)['object']['sha'] == os.environ['GITHUB_SHA'],
            'A newer main exists; revalidate that release instead of deploying an old approval.')
    provenance(os.environ['GITHUB_SHA'])


def deploy(manifest, digest):
    approved()
    stage('manifest_identity')
    require(manifest['source_sha'] == os.environ['GITHUB_SHA'] and manifest['run_id'] == os.environ['GITHUB_RUN_ID']
            and manifest['attempt'] == os.environ['GITHUB_RUN_ATTEMPT'])
    stage('request_payload')
    request = HOST.payload({'format': 1, 'revision': manifest['source_sha'], 'digest': digest,
        'image_id': manifest['image_id'], 'sql_set_sha256': manifest['sql_set_sha256'],
        'target': len(manifest['migrations']), 'request': manifest['run_id'] + '-' + manifest['attempt'],
        'expires_at': int(time.time()) + 6600})
    stage('request_encode')
    raw = base64.b64encode(HOST.canonical(request)).decode('ascii')
    stage('ssm_send')
    command = API.aws_json(['send-command', '--instance-ids', API.INSTANCE_ID,
        '--document-name', 'HobbyTree-Main-Release', '--document-version', '1',
        '--parameters', json.dumps({'ReleasePayload': [raw]}), '--timeout-seconds', '120',
        '--max-concurrency', '1', '--max-errors', '0', '--query', 'Command.CommandId'])
    stage('ssm_command_identity')
    require(isinstance(command, str) and re.fullmatch(r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}', command))
    print('MAIN_RELEASE_COMMAND=' + command, flush=True)
    end = time.monotonic() + 6000
    stage('ssm_observe')
    while time.monotonic() < end:
        try:
            result = API.aws_json(['get-command-invocation', '--command-id', command, '--instance-id', API.INSTANCE_ID,
                                   '--query', '{Status:Status,ResponseCode:ResponseCode}'])
        except API.InvocationNotVisible:
            result = {'Status': 'Pending', 'ResponseCode': -1}
        if result == {'Status': 'Success', 'ResponseCode': 0}:
            return
        require(result.get('Status') in API.PENDING, 'Remote release failed; inspect protected journal. Never resubmit blindly.')
        time.sleep(10)
    stage('ssm_observation_deadline')
    raise ValueError('Observation expired; remote work may continue. Reconcile before retry.')


def main():
    try:
        stage('entry')
        action = sys.argv[1]
        if action == 'provenance':
            stage('release_context')
            RELEASE.context()
            provenance(os.environ['GITHUB_SHA'])
        elif action == 'approved':
            approved()
        elif action == 'deploy':
            stage('artifact_manifest')
            directory = Path(os.environ['RUNNER_TEMP'], 'hobbytree-release/artifact')
            require(RELEASE.PUBLISH.sha(directory / 'release.json') == os.environ['MANIFEST_SHA'])
            stage('artifact_decode')
            deploy(json.loads((directory / 'release.json').read_bytes()), os.environ['IMAGE_DIGEST'])
        else:
            raise ValueError('Unsupported main release action')
        return 0
    except Exception as error:
        if isinstance(error, HTTPError):
            # Close the response so GC cannot emit a ResourceWarning containing its repr/message.
            with contextlib.suppress(Exception):
                error.close()
        print('MAIN_RELEASE_GATE_FAILED: ' + json.dumps(failure_diagnostic(error), sort_keys=True), file=sys.stderr)
        print('Preserve this run and inspect SSM/host evidence before any retry; no policy was changed.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
