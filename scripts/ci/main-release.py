"""Main-only approval and SSM transport; never mutate repository protection or AWS policy."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import time


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


def provenance(sha):
    """A merge commit from this repository's develop, with successful integration evidence."""
    pub = RELEASE.PUBLISH
    target = RELEASE.TARGET
    commit = pub.api_json('commits/' + sha, target)
    require(len(commit.get('parents', [])) == 2, 'A develop-to-main Merge Commit is required.')
    prs = pub.api_json('commits/' + sha + '/pulls?per_page=100', target)
    matched = [pr for pr in prs if pr.get('merged_at') and pr.get('merge_commit_sha') == sha
               and pr['base']['ref'] == 'main' and pr['head']['ref'] == 'develop'
               and pr['head']['repo']['full_name'] == RELEASE.REPOSITORY
               and pr['head']['sha'] == commit['parents'][1]['sha']]
    require(len(matched) == 1, 'Main release must originate in the reviewed develop PR.')
    head = commit['parents'][1]['sha']
    checks = pub.api_json('commits/' + head + '/check-runs?filter=latest&per_page=100', target)
    needed = {'Spotless Check', 'Docker amd64 and PostgreSQL 17', 'Flyway PostgreSQL 17 migrations'}
    passed = {c['name'] for c in checks.get('check_runs', []) if c.get('conclusion') == 'success'
              and c.get('head_sha') == head and c.get('app', {}).get('slug') == 'github-actions'}
    require(needed <= passed, 'Develop integration checks have not all succeeded on its exact head.')
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
    RELEASE.context()
    require(os.environ.get('HOBBYTREE_MAIN_RELEASE_ENABLED') == 'true', 'Production release is disabled.')
    pub, target = RELEASE.PUBLISH, RELEASE.TARGET
    protection(pub.api_json('environments/' + ENVIRONMENT, target),
               pub.api_json('environments/' + ENVIRONMENT + '/deployment-branch-policies?per_page=100', target))
    pub.validate_oidc(pub.api_json('actions/oidc/customization/sub', target), target)
    require(pub.api_json('git/ref/heads/main', target)['object']['sha'] == os.environ['GITHUB_SHA'],
            'A newer main exists; revalidate that release instead of deploying an old approval.')
    provenance(os.environ['GITHUB_SHA'])


def deploy(manifest, digest):
    approved()
    require(manifest['source_sha'] == os.environ['GITHUB_SHA'] and manifest['run_id'] == os.environ['GITHUB_RUN_ID']
            and manifest['attempt'] == os.environ['GITHUB_RUN_ATTEMPT'])
    request = HOST.payload({'format': 1, 'revision': manifest['source_sha'], 'digest': digest,
        'image_id': manifest['image_id'], 'sql_set_sha256': manifest['sql_set_sha256'],
        'target': len(manifest['migrations']), 'request': manifest['run_id'] + '-' + manifest['attempt'],
        'expires_at': int(time.time()) + 6600})
    raw = base64.b64encode(HOST.canonical(request)).decode('ascii')
    command = API.aws_json(['send-command', '--instance-ids', API.INSTANCE_ID,
        '--document-name', 'HobbyTree-Main-Release', '--document-version', '1',
        '--parameters', json.dumps({'ReleasePayload': [raw]}), '--timeout-seconds', '120',
        '--max-concurrency', '1', '--max-errors', '0', '--query', 'Command.CommandId'])
    require(isinstance(command, str) and re.fullmatch(r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}', command))
    print('MAIN_RELEASE_COMMAND=' + command, flush=True)
    end = time.monotonic() + 6000
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
    raise ValueError('Observation expired; remote work may continue. Reconcile before retry.')


if __name__ == '__main__':
    try:
        action = sys.argv[1]
        if action == 'provenance':
            RELEASE.context()
            provenance(os.environ['GITHUB_SHA'])
        elif action == 'approved':
            approved()
        elif action == 'deploy':
            directory = Path(os.environ['RUNNER_TEMP'], 'hobbytree-release/artifact')
            require(RELEASE.PUBLISH.sha(directory / 'release.json') == os.environ['MANIFEST_SHA'])
            deploy(json.loads((directory / 'release.json').read_bytes()), os.environ['IMAGE_DIGEST'])
        else:
            raise ValueError('Unsupported main release action')
    except Exception:
        print('MAIN_RELEASE_GATE_FAILED: check the phase evidence; no policy was changed.', file=sys.stderr)
        sys.exit(1)
