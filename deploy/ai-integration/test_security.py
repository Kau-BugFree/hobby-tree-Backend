import copy
from datetime import date
import unittest

from check_vulnerabilities import validate


class SecurityGate(unittest.TestCase):
    def setUp(self):
        self.digest = 'sha256:' + 'a' * 64
        self.item = dict(VulnerabilityID='CVE-2099-1234', PkgName='example',
                         InstalledVersion='1', Severity='HIGH')
        self.report = dict(SchemaVersion=2, ArtifactType='container_image',
                           Metadata=dict(ImageID=self.digest, OS=dict(Family='debian', Name='13.8'), DiffIDs=['layer']),
                           Results=[dict(Class='os-pkgs', Type='debian', Vulnerabilities=[self.item]),
                                    dict(Class='lang-pkgs', Type='python-pkg')])
        self.baseline = dict(reviewedOn='2026-11-27', expiresOn='2026-12-04',
                             images=dict(api=[['os-pkgs', 'debian', 'CVE-2099-1234', 'example', '1', 'HIGH']]))

    def check(self):
        return validate(self.report, self.baseline, 'api', self.digest, date(2026, 11, 27))

    def test_reviewed_and_resolved(self):
        self.assertEqual(self.check(), {'HIGH': 1, 'CRITICAL': 0})
        self.report['Results'][0]['Vulnerabilities'] = []
        self.assertEqual(self.check(), {'HIGH': 0, 'CRITICAL': 0})

    def test_changes_new_finding_and_fix_block(self):
        for key, value in [('VulnerabilityID', 'CVE-2099-9999'), ('PkgName', 'other'),
                           ('InstalledVersion', '2'), ('Severity', 'CRITICAL'), ('FixedVersion', '2')]:
            with self.subTest(key=key):
                item = copy.deepcopy(self.item)
                self.item[key] = value
                with self.assertRaises(ValueError): self.check()
                self.item.clear()
                self.item.update(item)

    def test_missing_or_wrong_scan_and_expired_review_block(self):
        for key, value in [('Results', []), ('ArtifactType', 'filesystem'), ('SchemaVersion', 1)]:
            original = self.report[key]
            self.report[key] = value
            with self.assertRaises(ValueError): self.check()
            self.report[key] = original
        self.report['Metadata']['ImageID'] = 'sha256:' + 'b' * 64
        with self.assertRaises(ValueError): self.check()
        self.report['Metadata']['ImageID'] = self.digest
        with self.assertRaises(ValueError):
            validate(self.report, self.baseline, 'api', self.digest, date(2026, 12, 5))
        self.report['Results'][0]['Type'] = 'python-pkg'
        with self.assertRaises(ValueError): self.check()

    def test_language_scan_cannot_be_omitted(self):
        self.report['Results'].pop()
        with self.assertRaises(ValueError): self.check()


if __name__ == '__main__':
    unittest.main()
