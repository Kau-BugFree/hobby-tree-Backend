import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from check_ecr_scan import timestamp, validate

ROOT = Path(__file__).resolve().parent


class EcrReceiptGate(unittest.TestCase):
    def setUp(self):
        self.report = json.loads((ROOT / 'fixtures/ecr-basic-reviewed.json').read_text(encoding='utf8'))
        self.baseline = json.loads((ROOT / 'ecr-security-review.json').read_text(encoding='utf8'))
        self.now = timestamp('2026-11-26T17:40:00Z')
        self.expected = dict(registry='123456789012', repository='synthetic/items',
                             digest='sha256:' + 'a' * 64, role='api')

    def check(self):
        return validate(self.report, self.baseline, now=self.now, **self.expected)

    def test_all_fifteen_and_resolved(self):
        self.assertEqual(self.check(), {'HIGH': 9, 'MEDIUM': 5, 'UNDEFINED': 1})
        self.report['imageScanFindings']['findings'] = []
        self.report['imageScanFindings']['findingSeverityCounts'] = {}
        self.assertEqual(self.check(), {})

    def test_identity_status_pagination_and_role(self):
        original = copy.deepcopy(self.report)
        for key, value in [('registryId', '222222222222'), ('repositoryName', 'wrong'),
                           ('imageId', {'imageDigest': 'sha256:' + 'b' * 64}),
                           ('imageScanStatus', {'status': 'IN_PROGRESS'}),
                           ('nextToken', 'more'), ('NextToken', 'cli-more')]:
            with self.subTest(key=key):
                self.report = copy.deepcopy(original)
                self.report[key] = value
                with self.assertRaises(ValueError): self.check()
        self.report = original
        self.expected['role'] = 'db'
        with self.assertRaisesRegex(ValueError, 'ROLE_NOT_REVIEWED'): self.check()

    def test_time_boundaries(self):
        for when in ('2026-11-26T17:20:00Z', '2026-11-27T17:40:00Z', '2026-11-30T15:00:00Z'):
            with self.subTest(when=when):
                self.now = timestamp(when)
                with self.assertRaises(ValueError): self.check()
        self.now = timestamp('2026-11-26T17:40:00Z')
        for key in ('imageScanCompletedAt', 'vulnerabilitySourceUpdatedAt'):
            original = self.report['imageScanFindings'][key]
            for value in (None, '2026-11-26T17:25:17', '2026-11-26T18:00:00Z'):
                self.report['imageScanFindings'][key] = value
                with self.assertRaises(ValueError): self.check()
            self.report['imageScanFindings'][key] = original

    def test_new_changed_or_fixable_finding(self):
        items = self.report['imageScanFindings']['findings']
        original = copy.deepcopy(items[0])
        for key, value in [('name', 'CVE-2099-1234'), ('severity', 'CRITICAL'), ('severity', 'LOW')]:
            items[0] = copy.deepcopy(original)
            items[0][key] = value
            with self.assertRaisesRegex(ValueError, 'UNREVIEWED_FINDING'): self.check()
        for key, value in [('package_name', 'other'), ('package_version', '3.5.10'),
                           ('fixed_version', '3.5.10'), ('fixed_in_version', '3.5.10')]:
            items[0] = copy.deepcopy(original)
            items[0]['attributes'] = [a for a in items[0]['attributes'] if a['key'] != key]
            items[0]['attributes'].append(dict(key=key, value=value))
            with self.assertRaises(ValueError): self.check()

    def test_partial_malformed_or_enhanced_receipts(self):
        original = copy.deepcopy(self.report['imageScanFindings'])
        for key, value in [('findings', original['findings'][:-1]), ('findings', None),
                           ('findingSeverityCounts', {'HIGH': 9}),
                           ('findingSeverityCounts', {'HIGH': True}),
                           ('findingSeverityCounts', {'HIGH': -1}), ('enhancedFindings', [])]:
            with self.subTest(key=key, value=str(value)[:30]):
                self.report['imageScanFindings'] = copy.deepcopy(original)
                self.report['imageScanFindings'][key] = value
                with self.assertRaises(ValueError): self.check()
        self.report['imageScanFindings'] = original
        original['findings'].append(copy.deepcopy(original['findings'][0]))
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_FINDING'): self.check()

    def test_missing_and_duplicate_package_attributes(self):
        attrs = self.report['imageScanFindings']['findings'][0]['attributes']
        attrs.append(dict(key='package_name', value='openssl'))
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_ATTRIBUTE'): self.check()
        attrs[:] = [a for a in attrs if a['key'] != 'package_name']
        with self.assertRaisesRegex(ValueError, 'INVALID_FINDING'): self.check()

    def test_actual_cli_fails_closed_without_logging_input(self):
        with tempfile.TemporaryDirectory() as temp:
            report = Path(temp) / 'bad.json'
            report.write_text('{"privateMarker":"must-not-appear"}', encoding='utf8')
            result = subprocess.run([sys.executable, str(ROOT / 'check_ecr_scan.py'), str(report),
                                     '--registry', self.expected['registry'], '--repository', self.expected['repository'],
                                     '--digest', self.expected['digest'], '--role', 'api'],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(json.loads(result.stdout)['status'], 'HOLD_REVIEW_REQUIRED')
            self.assertNotIn('must-not-appear', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
