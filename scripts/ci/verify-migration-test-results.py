#!/usr/bin/env python3
"""Fail closed if any migration scenario was omitted, skipped, or failed."""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

SUITE = 'com.hobbytree.hobbytree.migration.FlywayMigrationTests'
# Preserve the original regression safeguards and the API-21 timestamp scenarios.
# New @Test methods are discovered from source; additions need no numeric gate update.
REQUIRED = frozenset({
    'freshDatabaseRecordsAllChecksumsAndSecondMigrateIsEmpty',
    'v3UpgradePreservesAllEighteenPopulatedTablesAndHistory',
    'reportsCatalogAndSeparateAppPrivilegesAreExplicit',
    'reportChecksForeignKeyAndCascadeMatchTheServiceBoundary',
    'comingSoonAndCurrentHibernateValidateAcceptTheUpgradedSchema',
    'v5LockTimeoutRollsBackBothDdlAndHistoryAndCanBeRetried',
    'v4ForeignKeyLocksUsersAgainstWritersUntilTransactionEnds',
    'changedFixtureChecksumIsRejectedWithoutChangingOriginalSql',
    'unexpectedObjectsAndInvalidExistingDataFailWithoutRepair',
    'userHobbyUpdatedAtBackfillsOnlyKnownEvidenceAndPreservesExistingTreeAndActivityData',
    'userHobbyUpdatedAtDefaultAndTriggerWorkWithRestrictedAppWritesAndSkipSameValueChanges',
    'userHobbyUpdatedAtLockTimeoutRollsBackColumnBackfillAndTriggerAndCanBeRetried',
})


def require(condition: bool, message: object) -> None:
    if not condition:
        raise AssertionError(message)


def verify(source: Path, report_dir: Path) -> dict:
    text = source.read_text(encoding='utf-8')
    require(not re.search(r'(?m)^\s*@(ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b', text), 'Unsupported test type; extend migration report verification first')
    annotations = re.findall(r'(?m)^\s*@Test\s*$', text)
    methods = re.findall(r'(?m)^\s*@Test\s+void\s+(\w+)\s*\(', text)
    expected = set(methods)
    require(annotations and len(annotations) == len(methods) == len(expected), 'Unsupported or duplicate @Test declaration')
    require(REQUIRED <= expected, f'Critical migration scenarios removed: {sorted(REQUIRED - expected)}')
    reports = list(report_dir.glob('TEST-*FlywayMigrationTests.xml'))
    require(len(reports) == 1, 'Exactly one migration report is required')
    root = ET.parse(reports[0]).getroot()
    require(root.tag == 'testsuite' and root.attrib['name'] == SUITE, 'Unexpected migration suite')
    counts = {key: int(root.attrib[key]) for key in ('tests', 'skipped', 'failures', 'errors')}
    cases = root.findall('testcase')
    # JUnit renders injected @Test parameters too, e.g. method(Path) for @TempDir.
    display_names = [re.fullmatch(r'(\w+)\([^()]*\)', case.attrib['name']) for case in cases]
    require(all(display_names), 'Unsupported JUnit display name')
    actual = [match.group(1) for match in display_names]
    require(len(actual) == len(set(actual)), 'Duplicate migration cases')
    require(set(actual) == expected, f'Missing={sorted(expected - set(actual))}, unexpected={sorted(set(actual) - expected)}')
    require(counts['tests'] == len(cases) and not any(counts[key] for key in ('skipped', 'failures', 'errors')), counts)
    require(all(case.attrib['classname'] == SUITE for case in cases), 'Unexpected migration case class')
    require(all(case.find(tag) is None for case in cases for tag in ('failure', 'error', 'skipped')), 'Migration case did not pass')
    return counts


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=Path('src/test/java/com/hobbytree/hobbytree/migration/FlywayMigrationTests.java'))
    parser.add_argument('--report-dir', type=Path, default=Path('build/test-results/test'))
    args = parser.parse_args()
    print('MIGRATION_INTEGRATION_RESULT', json.dumps(verify(args.source, args.report_dir), sort_keys=True))
