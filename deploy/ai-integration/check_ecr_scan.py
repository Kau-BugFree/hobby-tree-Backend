"""Offline ECR BASIC receipt gate. All severities; no AWS calls or publication."""
import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re

SEVERITIES = {'CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFORMATIONAL', 'UNDEFINED'}


def timestamp(value):
    if not isinstance(value, str):
        raise ValueError('TIMESTAMP_REQUIRED')
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.utcoffset() is None:
        raise ValueError('TIMEZONE_REQUIRED')
    return result.astimezone(timezone.utc)


def validate(report, baseline, *, registry, repository, digest, role, now=None):
    now = now or datetime.now(timezone.utc)
    if (not re.fullmatch(r'[0-9]{12}', registry)
            or not re.fullmatch(r'sha256:[0-9a-f]{64}', digest) or not repository):
        raise ValueError('EXPECTED_IDENTITY_REQUIRED')
    if (report.get('registryId') != registry or report.get('repositoryName') != repository
            or report.get('imageId', {}).get('imageDigest') != digest):
        raise ValueError('SCAN_IDENTITY_MISMATCH')
    if report.get('imageScanStatus', {}).get('status') != 'COMPLETE':
        raise ValueError('SCAN_NOT_COMPLETE')
    if report.get('nextToken'):
        raise ValueError('PAGINATED_SCAN_INCOMPLETE')
    if not timestamp(baseline['reviewedAt']) <= now < timestamp(baseline['expiresAt']):
        raise ValueError('REVIEW_EXPIRED_OR_FUTURE')
    reviewed = baseline['images'].get(role)
    if reviewed is None:
        raise ValueError('ROLE_NOT_REVIEWED')
    reviewed = {tuple(row) for row in reviewed}
    scan = report.get('imageScanFindings', {})
    if 'enhancedFindings' in scan:
        raise ValueError('BASIC_SCAN_REQUIRED')
    for key in ('imageScanCompletedAt', 'vulnerabilitySourceUpdatedAt'):
        age = now - timestamp(scan.get(key))
        if not timedelta(0) <= age <= timedelta(hours=24):
            raise ValueError('SCAN_OR_FEED_STALE_OR_FUTURE')
    items = scan.get('findings')
    counts = scan.get('findingSeverityCounts')
    if not isinstance(items, list) or not isinstance(counts, dict):
        raise ValueError('FINDINGS_AND_COUNTS_REQUIRED')
    if any(key not in SEVERITIES or type(value) is not int or value < 0
           for key, value in counts.items()):
        raise ValueError('INVALID_COUNTS')
    observed, seen = Counter(), set()
    for item in items:
        attributes = item.get('attributes')
        if not isinstance(attributes, list):
            raise ValueError('ATTRIBUTES_REQUIRED')
        attrs = {}
        for attribute in attributes:
            key, value = attribute.get('key'), attribute.get('value')
            if not isinstance(key, str) or not isinstance(value, str) or key in attrs:
                raise ValueError('INVALID_OR_DUPLICATE_ATTRIBUTE')
            attrs[key] = value
        # BASIC may not supply a fix field: absence is NOT evidence of no fix.
        # Dated vendor review is mandatory and expires before the next release.
        if any(value for key, value in attrs.items() if 'fix' in key.lower()):
            raise ValueError('FIX_METADATA_REQUIRES_REVIEW')
        row = (item.get('name'), attrs.get('package_name'), attrs.get('package_version'), item.get('severity'))
        if not all(isinstance(value, str) and value for value in row) or row[3] not in SEVERITIES:
            raise ValueError('INVALID_FINDING')
        if row in seen:
            raise ValueError('DUPLICATE_FINDING')
        seen.add(row)
        if row not in reviewed:
            raise ValueError('UNREVIEWED_FINDING:' + row[0])
        observed[row[3]] += 1
    if dict(observed) != {key: value for key, value in counts.items() if value}:
        raise ValueError('TRUNCATED_OR_INCONSISTENT_COUNTS')
    return dict(observed)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('report', type=Path)
    parser.add_argument('--registry', required=True)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--digest', required=True, help='Verified linux/amd64 manifest, not OCI index')
    parser.add_argument('--role', choices=('api', 'db'), required=True)
    args = parser.parse_args()
    try:
        baseline = json.loads(Path(__file__).with_name('ecr-security-review.json').read_text(encoding='utf8'))
        counts = validate(json.loads(args.report.read_text(encoding='utf8')), baseline,
                          registry=args.registry, repository=args.repository, digest=args.digest, role=args.role)
    except (ValueError, KeyError, TypeError, AttributeError, OSError):
        print(json.dumps({'status': 'HOLD_REVIEW_REQUIRED', 'deploymentApproved': False}))
        return 1
    print(json.dumps({'status': 'REVIEWED_RESIDUALS_ONLY', 'counts': counts,
                      'deploymentApproved': False, 'publicationApproved': False}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
