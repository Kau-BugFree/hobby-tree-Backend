"""Fail closed on unreviewed/fixable HIGH/CRITICAL findings. Not deployment approval."""
import argparse
from datetime import date
import json
from pathlib import Path
import re

KEYS = ('VulnerabilityID', 'PkgName', 'InstalledVersion', 'Severity')


def finding_key(result, item):
    return (result['Class'], result['Type'], *(item[key] for key in KEYS))


def validate(report, baseline, role, image_id, today=None):
    today = today or date.today()
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', image_id):
        raise ValueError('IMAGE_ID_REQUIRED')
    metadata = report.get('Metadata', {})
    if (report.get('SchemaVersion') != 2 or report.get('ArtifactType') != 'container_image'
            or metadata.get('ImageID') != image_id
            or metadata.get('OS', {}).get('Family') != 'debian'
            or not metadata.get('OS', {}).get('Name', '').startswith('13.')
            or not metadata.get('DiffIDs')):
        raise ValueError('SCAN_IMAGE_OR_FORMAT_MISMATCH')
    if baseline['reviewedOn'] > today.isoformat() or today > date.fromisoformat(baseline['expiresOn']):
        raise ValueError('REVIEW_EXPIRED_OR_FUTURE')
    results = report.get('Results')
    if not isinstance(results, list) or not any(r.get('Class') == 'os-pkgs' and r.get('Type') == 'debian' for r in results):
        raise ValueError('OS_SCAN_MISSING')
    language = {'api': 'python-pkg', 'db': 'gobinary'}[role]
    if not any(r.get('Class') == 'lang-pkgs' and r.get('Type') == language for r in results):
        raise ValueError('LANGUAGE_SCAN_MISSING')
    reviewed = {tuple(row) for row in baseline['images'][role]}
    counts = {'HIGH': 0, 'CRITICAL': 0}
    for result in results:
        vulnerabilities = result.get('Vulnerabilities') or []
        if not isinstance(vulnerabilities, list):
            raise ValueError('INVALID_FINDINGS')
        for item in vulnerabilities:
            if item.get('Severity') not in counts:
                continue
            if item.get('FixedVersion'):
                raise ValueError('FIX_AVAILABLE:' + item['VulnerabilityID'])
            if finding_key(result, item) not in reviewed:
                raise ValueError('UNREVIEWED_FINDING:' + item['VulnerabilityID'])
            counts[item['Severity']] += 1
    return counts


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('report', type=Path)
    parser.add_argument('--role', choices=('api', 'db'), required=True)
    parser.add_argument('--image-id', required=True)
    args = parser.parse_args()
    baseline = json.loads(Path(__file__).with_name('security-review.json').read_text(encoding='utf8'))
    counts = validate(json.loads(args.report.read_text(encoding='utf8')), baseline, args.role, args.image_id)
    print(json.dumps({'status': 'REVIEWED_RESIDUALS_ONLY', 'role': args.role,
                      'counts': counts, 'deploymentApproved': False}))
