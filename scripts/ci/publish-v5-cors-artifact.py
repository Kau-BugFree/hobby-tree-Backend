"""Publish only the verified CORS archive after fail-closed ECR impact checks."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess


SPEC = importlib.util.spec_from_file_location('v5_artifact', Path(__file__).with_name('v5-artifact.py'))
V5 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(V5)
REVISION = '50f2fcd198241e8d7b6ab594057652bfc22a0d37'
OLD_DIGEST = 'sha256:11c492cc17e3fe781479bfa126bc1d6df2ec2ef3df074c4de5bd5f8d68dde608'
ARCHIVE_SHA256 = 'a9af3b85bc90545ccad03ce23c8a120494d9e2ec2957504e2ba9bf20110b6f3c'


def read_aws(*args):
    """Read one AWS response, stopping on CLI failure without exposing stderr."""
    result = subprocess.run(
        ['aws', *args, '--region', 'ap-northeast-2', '--output', 'json'],
        capture_output=True, text=True, timeout=30,
    )
    V5.require(result.returncode == 0, 'AWS read-only impact check denied or unavailable.')
    return json.loads(result.stdout)


def check_lifecycle_policy(response, account, repository):
    """Accept the target repository's single seven-day untagged expiration rule."""
    message = 'ECR lifecycle policy must expire only untagged images pushed more than seven days ago.'
    V5.require(isinstance(response, dict) and response.get('registryId') == account and
               response.get('repositoryName') == repository and
               isinstance(response.get('lifecyclePolicyText'), str), message)

    def unique_object(pairs):
        """Reject duplicate JSON keys before they can conceal another value."""
        value = dict(pairs)
        V5.require(len(value) == len(pairs), message)
        return value

    try:
        policy = json.loads(response['lifecyclePolicyText'], object_pairs_hook=unique_object)
    except (json.JSONDecodeError, RecursionError):
        raise V5.VerificationError(message) from None
    V5.require(isinstance(policy, dict) and policy.keys() == {'rules'} and
               isinstance(policy['rules'], list) and len(policy['rules']) == 1, message)
    rule = policy['rules'][0]
    V5.require(isinstance(rule, dict) and
               {'rulePriority', 'selection', 'action'} <= rule.keys() <=
               {'rulePriority', 'description', 'selection', 'action'} and
               type(rule['rulePriority']) is int and rule['rulePriority'] > 0 and
               isinstance(rule.get('description', ''), str), message)
    selection = rule['selection']
    V5.require(isinstance(selection, dict) and type(selection.get('countNumber')) is int, message)
    approved = {'tagStatus': 'untagged', 'countType': 'sinceImagePushed',
                'countUnit': 'days', 'countNumber': 7}
    V5.require(selection in (approved, dict(approved, storageClass='standard')) and
               rule['action'] == {'type': 'expire'}, message)


def check_push_impact(expected):
    """Check the publisher identity, registry policy, event routes and rollback image."""
    account = expected['account_id']
    repository = expected['ecr_repository']
    caller = read_aws('sts', 'get-caller-identity')
    V5.require(caller.get('Account') == account and
               caller.get('Arn', '').startswith(
                   f'arn:aws:sts::{account}:assumed-role/hobbytree-github-ecr-push-role/'),
               'Wrong AWS publication identity.')
    policy = read_aws('ecr', 'get-lifecycle-policy', '--registry-id', account,
                      '--repository-name', repository)
    check_lifecycle_policy(policy, account, repository)
    registry = read_aws('ecr', 'describe-registry')
    V5.require(registry.get('registryId') == account and
               registry.get('replicationConfiguration', {}).get('rules') == [],
               'ECR replication requires independent review.')
    rules = read_aws('events', 'list-rules', '--event-bus-name', 'default')
    V5.require(isinstance(rules.get('Rules'), list) and not rules.get('NextToken'),
               'EventBridge impact could not be verified.')
    for rule in rules['Rules']:
        if rule.get('State') == 'DISABLED' or not rule.get('EventPattern'):
            continue
        pattern = json.loads(rule['EventPattern'])
        sources = pattern.get('source')
        V5.require(isinstance(sources, list) and bool(sources) and
                   all(isinstance(source, str) and source not in {'aws.ecr', 'aws.inspector2'} for source in sources) and
                   '$or' not in pattern, 'A potentially matching EventBridge rule requires review.')
    previous = json.loads(V5.aws('batch-get-image', expected, '--image-ids', 'imageDigest=' + OLD_DIGEST))
    V5.require(not previous.get('failures') and len(previous.get('images', [])) == 1 and
               previous['images'][0].get('imageId', {}).get('imageDigest') == OLD_DIGEST,
               'Original V5 rollback image unavailable.')


def main():
    """Recheck the exact hotfix and publish only after verifying its AWS impact."""
    expected = V5.lock()
    V5.require(os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' and
               os.environ.get('GITHUB_REPOSITORY') == expected['repository'] and
               os.environ.get('GITHUB_REF') == 'refs/heads/develop' and
               os.environ.get('V5_CORS_ARTIFACT_PUBLISH_ENABLED') == 'true' and
               os.environ.get('CONFIRM_HOTFIX_SHA') == REVISION, 'Unapproved hotfix publication context.')
    directory = Path(os.environ['RUNNER_TEMP']) / 'v5-cors-original'
    archive = directory / 'backend-linux-amd64.tar.gz'
    V5.require(V5.sha(directory / 'artifact.zip') ==
               '4ba099aa6e408facc338f74a6f61d95a580cb818427b9cc97cdb77ccbd901573',
               'Original artifact SHA256 mismatch.')
    V5.require(V5.sha(archive) == ARCHIVE_SHA256, 'Original image archive SHA256 mismatch.')
    expected.update(source_sha=REVISION, workflow_sha='500b3d3f6dda453cb4618651a70c111d4faf8606',
                    run_id=42062701908, run_attempt=1, artifact_id=13432224738,
                    archive_sha256=ARCHIVE_SHA256,
                    image_id='sha256:5203315180507a1ca448e220ab07d5edaf252eccaadefe053435d38f122d35d8')
    V5.verify_inspect(json.loads(V5.command(['docker', 'image', 'inspect', expected['image_id']])), expected)
    check_push_impact(expected)
    V5.publish(expected)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('FAIL: ' + (str(error) if isinstance(error, V5.VerificationError) else type(error).__name__))
        raise SystemExit(1) from None
