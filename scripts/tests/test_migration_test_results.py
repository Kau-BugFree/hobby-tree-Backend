"""Regression checks for missing, forged, skipped, and failed migration evidence."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('migration_results', ROOT / 'scripts/ci/verify-migration-test-results.py')
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class MigrationResultTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.source = self.directory / 'FlywayMigrationTests.java'
        self.report = self.directory / ('TEST-' + GATE.SUITE + '.xml')
        self.names = sorted(GATE.REQUIRED)
        self.write_source(self.names)
        self.write_report(self.names)

    def write_source(self, names):
        self.source.write_text('\n'.join('@Test\nvoid ' + name + '() {}' for name in names), encoding='utf-8')

    def write_report(self, names):
        root = ET.Element('testsuite', name=GATE.SUITE, tests=str(len(names)), failures='0', errors='0', skipped='0')
        for name in names:
            ET.SubElement(root, 'testcase', name=name + '()', classname=GATE.SUITE)
        ET.ElementTree(root).write(self.report, encoding='utf-8')

    def change_report(self, change):
        tree = ET.parse(self.report)
        change(tree.getroot())
        tree.write(self.report, encoding='utf-8')

    def verify(self):
        return GATE.verify(self.source, self.directory)

    def test_accepts_all_required_scenarios(self):
        self.assertEqual(self.verify()['tests'], len(self.names))

    def test_accepts_added_scenario_without_updating_a_numeric_count(self):
        names = self.names + ['newMigrationScenario']
        self.write_source(names)
        self.write_report(names)
        self.assertEqual(self.verify()['tests'], len(names))

    def test_accepts_junit_injected_parameter_names(self):
        self.change_report(lambda root: root.find('testcase').set('name', self.names[0] + '(Path)'))
        self.assertEqual(self.verify()['tests'], len(self.names))

    def test_rejects_an_unknown_junit_display_format(self):
        self.change_report(lambda root: root.find('testcase').set('name', 'custom display name'))
        with self.assertRaises(AssertionError):
            self.verify()

    def test_rejects_missing_report(self):
        self.report.unlink()
        with self.assertRaises(AssertionError):
            self.verify()

    def test_rejects_removed_critical_scenario_even_if_source_and_report_agree(self):
        names = self.names[1:] + ['replacementScenario']
        self.write_source(names)
        self.write_report(names)
        with self.assertRaises(AssertionError):
            self.verify()

    def test_rejects_a_source_scenario_missing_from_the_report(self):
        self.write_source(self.names + ['newMigrationScenario'])
        with self.assertRaises(AssertionError):
            self.verify()

    def test_rejects_duplicate_cases(self):
        self.write_report(self.names + [self.names[0]])
        with self.assertRaises(AssertionError):
            self.verify()

    def test_rejects_misleading_header_counts(self):
        self.change_report(lambda root: root.set('tests', '0'))
        with self.assertRaises(AssertionError):
            self.verify()

    def test_rejects_failure_error_or_skip_even_with_zero_header_counts(self):
        for tag in ('failure', 'error', 'skipped'):
            with self.subTest(tag=tag):
                self.write_report(self.names)
                self.change_report(lambda root: ET.SubElement(root.find('testcase'), tag))
                with self.assertRaises(AssertionError):
                    self.verify()

    def test_rejects_nonzero_failure_error_or_skip_headers(self):
        for tag in ('failures', 'errors', 'skipped'):
            with self.subTest(tag=tag):
                self.write_report(self.names)
                self.change_report(lambda root: root.set(tag, '1'))
                with self.assertRaises(AssertionError):
                    self.verify()

    def test_rejects_a_foreign_case_class(self):
        self.change_report(lambda root: root.find('testcase').set('classname', 'other.Suite'))
        with self.assertRaises(AssertionError):
            self.verify()

    def test_rejects_an_unsupported_test_annotation(self):
        with self.source.open('a', encoding='utf-8') as stream:
            stream.write('\n@ParameterizedTest\nvoid additionalCase() {}')
        with self.assertRaises(AssertionError):
            self.verify()

    def test_rejects_missing_counter_attributes(self):
        self.change_report(lambda root: root.attrib.pop('skipped'))
        with self.assertRaises(KeyError):
            self.verify()


if __name__ == '__main__':
    unittest.main()
