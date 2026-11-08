#!/usr/bin/env python3
"""Render a PROPOSED document with exact source hashes; does not access AWS."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
base = '/opt/hobbytree/scripts/deploy-schema'
commands = ['set -eu', 'test "$(id -u)" = 0',
            'for path in /opt /opt/hobbytree /opt/hobbytree/scripts ' + base + '; do test ! -L "$path"; test "$(stat -c %u:%g:%a "$path")" = 0:0:755; done']
for name in ('ec2-release.py', 'schema-transition.py'):
    sha = hashlib.sha256((root / 'scripts/deploy' / name).read_bytes()).hexdigest()
    path = base + '/' + name
    commands += ['test ! -L ' + path, 'test "$(stat -c %u:%g:%a ' + path + ')" = 0:0:500',
                 "printf '%s  %s\\n' '" + sha + "' '" + path + "' | sha256sum --check --status"]
commands += [': "${SSM_RequestId:?ENV_VAR support required}" "${SSM_PlanSha256:?}" "${SSM_Phase:?}" "${SSM_Approval:?}" "${SSM_ApprovalId:?}"',
             'exec env -i PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin HOME=/root LANG=C.UTF-8 /usr/bin/python3 -I ' + base + '/schema-transition.py --request "$SSM_RequestId" --plan-sha256 "$SSM_PlanSha256" --phase "$SSM_Phase" --approval "$SSM_Approval" --approval-id "$SSM_ApprovalId"']
patterns = {'RequestId': '^[0-9]{1,20}-[0-9]{1,5}$', 'PlanSha256': '^[0-9a-f]{64}$',
            'ApprovalId': '^[0-9a-f]{32}$',
            'Phase': '^(quiesce|verify-writers|retire-guard|migrate|verify-activation|deploy)$',
            'Approval': '^(approve-maintenance-and-backup-window|approve-database-writer-fence-verification|approve-snapshotted-v5-guard-retirement-no-image-rollback|approve-external-app-connect-verification|approve-backed-up-schema-change-no-image-rollback|approve-verified-schema-image-activation)$'}
document = {'schemaVersion': '2.2', 'description': 'PROPOSED separate approved schema transition. Not installed. No automatic V5 rollback.',
            'parameters': {k: {'type': 'String', 'interpolationType': 'ENV_VAR', 'allowedPattern': v} for k, v in patterns.items()},
            'mainSteps': [{'action': 'aws:runShellScript', 'name': 'approvedSchemaTransition',
                           'precondition': {'StringEquals': ['platformType', 'Linux']},
                           'inputs': {'timeoutSeconds': '2700', 'runCommand': commands}}]}
path = root / 'deploy/ssm/HobbyTree-Dev-SchemaTransition.proposed.json'
path.write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8')
print('Rendered proposed SSM document; no AWS changes.')
