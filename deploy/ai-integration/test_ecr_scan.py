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
        self.baseline['repositories'] = {'api': 'synthetic/items', 'db': 'synthetic/catalog'}
        self.now = timestamp('2026-11-26T18:10:00Z')
        self.expected = dict(registry='123456789012', repository='synthetic/items',
                             digest='sha256:' + 'a' * 64, role='api')

    def check(self):
        return validate(self.report, self.baseline, now=self.now, **self.expected)

    def test_all_fifteen_and_resolved(self):
        self.assertEqual(self.check(), {'HIGH': 9, 'MEDIUM': 5, 'UNDEFINED': 1})
        self.report['imageScanFindings']['findings'] = []
        self.report['imageScanFindings']['findingSeverityCounts'] = {}
        self.assertEqual(self.check(), {})

    def test_sasl_requires_exact_review(self):
        finding = dict(name='CVE-2026-107161', severity='HIGH', attributes=[
            dict(key='package_name', value='cyrus-sasl2'),
            dict(key='package_version', value='2.1.28+dfsg1-9')])
        self.report['imageScanFindings']['findings'].append(finding)
        self.report['imageScanFindings']['findingSeverityCounts']['HIGH'] = 10
        self.assertEqual(self.check(), {'HIGH': 10, 'MEDIUM': 5, 'UNDEFINED': 1})
        self.baseline['images']['api'] = [row for row in self.baseline['images']['api']
                                          if row[0] != finding['name']]
        with self.assertRaisesRegex(ValueError, 'UNREVIEWED_FINDING'): self.check()

    def test_sasl_changed_version_or_fix_stops(self):
        for attribute in (dict(key='package_version', value='2.1.28+dfsg1-12'),
                          dict(key='fixed_version', value='2.1.28+dfsg1-12')):
            finding = dict(name='CVE-2026-107161', severity='HIGH', attributes=[
                dict(key='package_name', value='cyrus-sasl2'),
                dict(key='package_version', value='2.1.28+dfsg1-9')])
            finding['attributes'] = [a for a in finding['attributes'] if a['key'] != attribute['key']]
            finding['attributes'].append(attribute)
            self.report['imageScanFindings']['findings'][-1] = finding
            reason = ('UNREVIEWED_FINDING' if attribute['key'] == 'package_version'
                      else 'FIX_METADATA_REQUIRES_REVIEW')
            with self.assertRaisesRegex(ValueError, reason): self.check()

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
        with self.assertRaisesRegex(ValueError, 'ROLE_REPOSITORY_MISMATCH'): self.check()
        self.expected['role'] = 'unknown'
        with self.assertRaisesRegex(ValueError, 'ROLE_NOT_REVIEWED'): self.check()

    def use_db(self):
        self.report = json.loads((ROOT / 'fixtures/ecr-basic-db-reviewed.json').read_text(encoding='utf8'))
        self.expected.update(repository='synthetic/catalog', digest='sha256:' + 'b' * 64, role='db')
        self.now = timestamp('2026-11-27T00:10:00Z')

    def test_independent_db_full_receipt(self):
        self.use_db()
        self.assertEqual(self.check(), {'HIGH': 18, 'MEDIUM': 16, 'LOW': 4, 'UNDEFINED': 1})
        self.expected['role'] = 'api'
        with self.assertRaisesRegex(ValueError, 'ROLE_REPOSITORY_MISMATCH'): self.check()

    def test_each_new_db_finding_needs_exact_review(self):
        self.use_db()
        rows = self.baseline['images']['db']
        new = {'CVE-2026-' + suffix for suffix in (
            '127039', '127040', '57062', '105712', '76781', '86137', '86141',
            '137067', '137068', '137069', '137072', '137073', '137074', '137075', '137082')}
        self.assertEqual(len([r for r in rows if r[0] in new]), 15)
        for cve in sorted(new):
            with self.subTest(cve=cve):
                self.baseline['images']['db'] = [r for r in rows if r[0] != cve]
                with self.assertRaisesRegex(ValueError, 'UNREVIEWED_FINDING:' + cve): self.check()
        self.baseline['images']['db'] = rows

    def test_db_changes_fixes_and_stale_scan_stop(self):
        self.use_db()
        original = copy.deepcopy(self.report)
        for attribute in (dict(key='package_version', value='new-version'),
                          dict(key='fixed_version', value='fixed-version')):
            self.report = copy.deepcopy(original)
            attrs = self.report['imageScanFindings']['findings'][0]['attributes']
            attrs[:] = [a for a in attrs if a['key'] != attribute['key']]
            attrs.append(attribute)
            with self.assertRaises(ValueError): self.check()
        self.report = original
        self.now = timestamp('2026-11-28T00:10:00Z')
        with self.assertRaisesRegex(ValueError, 'STALE'): self.check()

    def test_time_boundaries(self):
        for when in ('2026-11-26T17:20:00Z', '2026-11-27T17:40:00Z', '2026-11-30T15:00:00Z'):
            with self.subTest(when=when):
                self.now = timestamp(when)
                with self.assertRaises(ValueError): self.check()
        self.now = timestamp('2026-11-26T18:10:00Z')
        for key in ('imageScanCompletedAt', 'vulnerabilitySourceUpdatedAt'):
            original = self.report['imageScanFindings'][key]
            for value in (None, '2026-11-26T17:25:17', '2026-11-26T19:00:00Z'):
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
