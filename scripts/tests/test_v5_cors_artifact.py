"""Regression tests for the hotfix publisher's fail-closed lifecycle check."""

import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch


PATH = Path(__file__).resolve().parents[1] / 'ci' / 'publish-v5-cors-artifact.py'
SPEC = importlib.util.spec_from_file_location('cors_publish', PATH)
PUBLISHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISHER)
EXPECTED = PUBLISHER.V5.lock()
ACCOUNT = EXPECTED['account_id']
REPOSITORY = EXPECTED['ecr_repository']


def approved_rule():
    return {
        'rulePriority': 1,
        'selection': {'tagStatus': 'untagged', 'countType': 'sinceImagePushed',
                      'countUnit': 'days', 'countNumber': 7},
        'action': {'type': 'expire'},
    }


def response(policy):
    return {'registryId': ACCOUNT, 'repositoryName': REPOSITORY,
            'lifecyclePolicyText': json.dumps(policy)}


class LifecyclePolicyTests(unittest.TestCase):
    def check(self, result):
        PUBLISHER.check_lifecycle_policy(result, ACCOUNT, REPOSITORY)

    def test_accepts_exact_rule_and_semantically_neutral_fields(self):
        self.check(response({'rules': [approved_rule()]}))
        rule = dict(approved_rule(), description='Prune unused images', rulePriority=20)
        rule['selection']['storageClass'] = 'standard'
        self.check(response({'rules': [rule]}))

    def test_rejects_other_selection_and_action(self):
        for change in (
                {'tagStatus': 'tagged'}, {'tagStatus': 'any'}, {'countNumber': 6},
                {'countNumber': 7.0}, {'countUnit': 'hours'}, {'countType': 'sinceImagePulled'},
                {'tagPrefixList': ['sha']}, {'tagPatternList': ['*']}, {'storageClass': 'archive'},
                {'futureField': True}):
            with self.subTest(change=change):
                rule = approved_rule()
                rule['selection'].update(change)
                with self.assertRaises(PUBLISHER.V5.VerificationError):
                    self.check(response({'rules': [rule]}))
        for action in ({'type': 'transition'}, {'type': 'expire', 'targetStorageClass': 'archive'}):
            with self.subTest(action=action), self.assertRaises(PUBLISHER.V5.VerificationError):
                self.check(response({'rules': [dict(approved_rule(), action=action)]}))

    def test_rejects_extra_rules_and_unknown_or_malformed_policy(self):
        valid = response({'rules': [approved_rule()]})
        for result in (
                None, {}, dict(valid, registryId='0'), dict(valid, repositoryName='another/repo'),
                dict(valid, lifecyclePolicyText=None), dict(valid, lifecyclePolicyText='{'),
                dict(valid, lifecyclePolicyText='{"rules":[],"rules":[]}'),
                response([]), response({'rules': []}),
                response({'rules': [approved_rule(), approved_rule()]}),
                response({'rules': [approved_rule()], 'unknown': True}),
                response({'rules': [dict(approved_rule(), unknown=True)]}),
                response({'rules': [dict(approved_rule(), rulePriority=True)]}),
                response({'rules': [dict(approved_rule(), description=5)]})):
            with self.subTest(result=result), self.assertRaises(PUBLISHER.V5.VerificationError):
                self.check(result)

    def test_lookup_failure_stops_before_following_aws_checks(self):
        caller = {'Account': ACCOUNT,
                  'Arn': f'arn:aws:sts::{ACCOUNT}:assumed-role/hobbytree-github-ecr-push-role/test'}
        for error in ('AccessDeniedException', 'LifecyclePolicyNotFoundException', 'ServiceUnavailableException'):
            with self.subTest(error=error), patch.object(
                    PUBLISHER.subprocess, 'run', side_effect=[
                        subprocess.CompletedProcess([], 0, json.dumps(caller), ''),
                        subprocess.CompletedProcess([], 254, '', f'An error occurred ({error})'),
                    ]) as aws, patch.object(PUBLISHER.V5, 'aws') as subsequent_lookup:
                with self.assertRaises(PUBLISHER.V5.VerificationError):
                    PUBLISHER.check_push_impact(EXPECTED)
                self.assertEqual(aws.call_count, 2)
                self.assertEqual(aws.call_args.args[0][:3], ['aws', 'ecr', 'get-lifecycle-policy'])
                subsequent_lookup.assert_not_called()

    def test_approved_policy_still_requires_other_impact_checks(self):
        caller = {'Account': ACCOUNT,
                  'Arn': f'arn:aws:sts::{ACCOUNT}:assumed-role/hobbytree-github-ecr-push-role/test'}
        registry = {'registryId': ACCOUNT, 'replicationConfiguration': {'rules': []}}
        previous = {'images': [{'imageId': {'imageDigest': PUBLISHER.OLD_DIGEST}}]}
        with patch.object(PUBLISHER, 'read_aws', side_effect=[
                caller, response({'rules': [approved_rule()]}), registry, {'Rules': []},
        ]) as read, patch.object(PUBLISHER.V5, 'aws', return_value=json.dumps(previous)) as lookup:
            PUBLISHER.check_push_impact(EXPECTED)
        self.assertEqual(read.call_count, 4)
        lookup.assert_called_once_with(
            'batch-get-image', EXPECTED, '--image-ids', 'imageDigest=' + PUBLISHER.OLD_DIGEST)


if __name__ == '__main__':
    unittest.main()
