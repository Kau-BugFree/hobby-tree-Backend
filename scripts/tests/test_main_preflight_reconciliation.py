"""AWS-free regression tests; real JAR bytes and durable filesystem evidence."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('reconciliation_test', ROOT / 'scripts/deploy/reconcile_main_preflight.py')
R = importlib.util.module_from_spec(spec)
sys.path.insert(0, str(ROOT / 'scripts/deploy'))
try:
    spec.loader.exec_module(R)
finally:
    sys.path.pop(0)
H = R.H


class MigrationBytesTests(unittest.TestCase):
    def rows(self, newline, count=24, transform=lambda raw: raw):
        with tempfile.TemporaryDirectory() as tmp:
            jar = Path(tmp) / 'app.jar'
            with zipfile.ZipFile(jar, 'w') as out:
                for i in range(1, count + 1):
                    out.writestr(f'BOOT-INF/classes/db/migration/V{i}__fixture.sql',
                                 transform(f'-- fixture{newline}SELECT {i};{newline}'.encode()))
            return H.jar_history(jar)

    def verify(self, old, new, **changes):
        H.verify_history(old, new, {'migrations': old},
                         {**{'target': len(new), 'sql_set_sha256': H.fingerprint(new)}, **changes})

    def test_crlf_to_lf_all_applied_versions_and_new_v25_pass(self):
        old, new = self.rows('\r\n'), self.rows('\n', 25)
        self.assertTrue(all(a['sha256'] != b['sha256'] for a, b in zip(old, new)))
        self.verify(old, new)
        self.verify(new[:24], old)

    def test_same_image_sql_bytes_pass(self):
        rows = self.rows('\n')
        self.verify(rows, rows)

    def test_content_blank_line_and_whitespace_changes_fail_even_with_equal_crc(self):
        old = self.rows('\r\n')
        for change in (lambda b: b.replace(b'SELECT', b'SELECT  '),
                       lambda b: b.replace(b'SELECT', b'\nSELECT'),
                       lambda b: b.replace(b'SELECT 1;', b'SELECT 9;')):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.verify(old, self.rows('\n', transform=change))
        blank = self.rows('\n', transform=lambda b: b.replace(b'SELECT', b'\nSELECT'))
        self.assertEqual([r['crc'] for r in old], [r['crc'] for r in blank])

    def test_rename_reorder_removal_crc_and_missing_normalized_hash_fail(self):
        old = self.rows('\n')
        bad = []
        for key, val in (('name', 'V1__renamed.sql'), ('crc', 0), ('normalized_sha256', '0'*64)):
            rows = copy.deepcopy(old)
            rows[0][key] = val
            bad.append(rows)
        bad += [list(reversed(old)), old[:-1]]
        for rows in bad:
            with self.subTest(rows=rows[0]), self.assertRaises(ValueError):
                self.verify(old, rows)
        rows = copy.deepcopy(old)
        del rows[0]['normalized_sha256']
        with self.assertRaises(KeyError):
            H.verify_history(old, rows, {'migrations': old}, {'target': 24})

    def test_previous_raw_evidence_and_candidate_approved_fingerprint_remain_strict(self):
        old, new = self.rows('\r\n'), self.rows('\n', 25)
        tampered = copy.deepcopy(old)
        tampered[0]['sha256'] = '0'*64
        with self.assertRaises(ValueError):
            H.verify_history(old, new, {'migrations': tampered}, {'target': 25})
        with self.assertRaises(ValueError):
            self.verify(old, new, sql_set_sha256='0'*64)
        with self.assertRaises(ValueError):
            self.verify(old, new, target=26)

    def test_real_preflight_reaches_only_before_db_mode_after_crlf_lf_validation(self):
        old, new = self.rows('\r\n'), self.rows('\n', 25)
        active = {'migrations': old, 'digest': 'old', 'image_id': 'old-id', 'revision': 'old-revision'}
        request = {'target': 25, 'sql_set_sha256': H.fingerprint(new), 'digest': 'new',
                   'image_id': 'new-id', 'revision': 'new-revision', 'expires_at': int(time.time()) + 3600}
        cfg = {'enabled': True, 'protected_files': {}, 'instance': 'fixture', 'db_host': 'fixture'}
        env = {'SPRING_FLYWAY_ENABLED': 'false', 'SPRING_JPA_HIBERNATE_DDL_AUTO': 'validate',
               'DB_URL': 'jdbc:postgresql://fixture:5432/hobbytree?sslmode=verify-full&sslrootcert=/etc/hobbytree/certs/rds-ca.pem'}
        model = {'services': {'redis': {}, 'backend': {'user': '10001:10001', 'read_only': True,
                 'restart': 'no', 'environment': env}}}
        host = H.Host(cfg, Path('/unused'))
        host.container = Mock(return_value={'Config': {'Image': H.REGISTRY + '@old', 'Env': [k+'='+v for k, v in env.items()]},
                              'Image': 'old-id', 'HostConfig': {'RestartPolicy': {'Name': 'no'}},
                              'State': {'Health': {'Status': 'healthy'}}})
        host.aws = Mock(return_value={'Account': '494915081306'})
        host.image = Mock(side_effect=[old, new])
        host.compose = Mock(return_value=['docker', 'compose'])
        host.command = Mock(side_effect=[b'ActiveState=inactive\nUnitFileState=disabled\n', b'fixture', b'', H.canonical(model)])
        host.database = Mock()
        protected = Mock(read_bytes=Mock(return_value=b'{"status":"main-release-owned"}'), read_text=Mock(return_value='fixture'))
        with patch.object(H, 'configuration', side_effect=lambda cfg: cfg), patch.object(H, 'protected', return_value=protected):
            host.preflight(request, active)
        host.database.assert_called_once_with('before')
        self.assertEqual(host.after, new)


@unittest.skipUnless(os.name == 'posix', 'Durable directory fsync and flock require Linux')
class ReconciliationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / 'main-release'
        self.state.mkdir()
        (self.root / 'lock').touch()
        self.failed = self.state / '123-1'
        self.failed.mkdir()
        self.config_path = self.root / 'config.json'
        self.request = dict(format=1, revision='a'*40, digest='sha256:'+'b'*64, image_id='sha256:'+'c'*64,
                            sql_set_sha256='d'*64, target=25, request='123-1', expires_at=1000)
        self.previous = dict(status='complete', request='100-1', digest='sha256:'+'e'*64,
                             image_id='sha256:'+'f'*64, revision='1'*40, migrations=[{}]*24)
        self.blocked = {**self.previous, 'status': 'blocked', 'request': '123-1'}
        self.journal = {'request': self.request, 'events': [{'phase': 'preflight', 'status': 'failed', 'at': 900}]}
        checksum = H.sha(Path(H.__file__).read_bytes())
        self.config = {'enabled': False, 'runtime': {'enabled': False, 'sources': {'host': checksum}}, 'executor_sha256': checksum}
        self.write(self.config_path, self.config)
        self.paths = {'active': self.state / 'active.json', 'journal': self.failed / 'journal.json',
                      'previous': self.failed / 'previous.json', 'request': self.failed / 'request.json'}
        for key, value in (('active', self.blocked), ('journal', self.journal), ('previous', self.previous), ('request', self.request)):
            self.write(self.paths[key], value)
        self.original = {k: p.read_bytes() for k, p in self.paths.items()}
        self.pins = {k: H.sha(self.original[k]) for k in ('active', 'journal', 'previous')}
        self.host = Mock()
        self.host.command.return_value = b'ActiveState=inactive\nUnitFileState=disabled\n'
        self.observe = {'backend': {'Id': 'b'*64}, 'redis': {'Id': 'c'*64}, 'boot_id': 'fixture'}
        for patcher in (patch.object(H, 'ROOT', self.root), patch.object(H, 'STATE', self.state),
                        patch.object(H, 'CONFIG', self.config_path), patch.object(H, 'protected', side_effect=Path),
                        patch.object(H, 'configuration'), patch.object(H, 'Host', return_value=self.host),
                        patch.object(R, 'observation', return_value=self.observe)):
            patcher.start()
            self.addCleanup(patcher.stop)

    def write(self, path, value):
        path.write_bytes(H.canonical(value))

    def run_reconcile(self):
        R.reconcile('123-1', self.pins)

    def unchanged(self):
        for key, path in self.paths.items():
            self.assertEqual(path.read_bytes(), self.original[key], key)

    def test_success_preserves_all_failed_evidence_and_only_restores_previous_active(self):
        self.run_reconcile()
        self.assertEqual(self.paths['active'].read_bytes(), self.original['previous'])
        for key in ('journal', 'previous', 'request'):
            self.assertEqual(self.paths[key].read_bytes(), self.original[key])
        self.assertEqual(self.config_path.read_bytes(), H.canonical(self.config))
        self.host.preflight.assert_called_once()
        renewed, previous = self.host.preflight.call_args.args
        self.assertGreater(renewed['expires_at'], self.request['expires_at'])
        self.assertEqual({**renewed, 'expires_at': self.request['expires_at']}, self.request)
        self.assertEqual(previous, self.previous)
        self.host.perform.assert_not_called()
        self.host.restore.assert_not_called()
        self.host.database.assert_not_called()  # Only the real Host.preflight owns DB mode 'before'.
        self.host.target.assert_not_called()
        self.host.probes.assert_any_call('https://api.hobbytreeapp.com')
        self.assertTrue((self.state / 'reconcile-123-1/verified.json').exists())
        with self.assertRaises(ValueError):
            self.run_reconcile()

    def test_preflight_db_or_probes_failure_preserves_block_and_removes_temporary_credentials(self):
        def fail(*_):
            (self.state / 'reconcile-123-1/docker/config.json').write_text('DO_NOT_LOG_SECRET')
            raise ValueError('DO_NOT_LOG_SECRET')
        self.host.preflight.side_effect = fail
        with self.assertRaises(ValueError):
            self.run_reconcile()
        self.unchanged()
        audit = self.state / 'reconcile-123-1'
        self.assertFalse((audit / 'docker/config.json').exists())
        self.assertNotIn('DO_NOT_LOG_SECRET', (audit / 'failed.json').read_text())
        self.assertFalse((audit / 'verified.json').exists())
        self.host.perform.assert_not_called()

    def test_public_probe_failure_and_service_change_fail_closed(self):
        self.host.probes.side_effect = ValueError('health')
        with self.assertRaises(ValueError):
            self.run_reconcile()
        self.unchanged()

    def test_container_or_boot_drift_fails_closed(self):
        with patch.object(R, 'observation', side_effect=[self.observe, {**self.observe, 'boot_id': 'other'}]):
            with self.assertRaises(ValueError):
                self.run_reconcile()
        self.unchanged()

    def test_configuration_drift_before_commit_fails_closed(self):
        self.host.probes.side_effect = lambda *_: self.write(self.config_path, {**self.config, 'enabled': True})
        with self.assertRaises(ValueError):
            self.run_reconcile()
        self.unchanged()

    def test_failure_evidence_drift_before_commit_fails_closed(self):
        self.host.probes.side_effect = lambda *_: self.write(self.paths['journal'], {'tampered': True})
        with self.assertRaises(ValueError):
            self.run_reconcile()
        self.assertEqual(self.paths['active'].read_bytes(), self.original['active'])

    def test_post_preflight_or_ambiguous_events_are_ineligible_even_with_valid_pins(self):
        for events in ([], [{'phase': 'preflight', 'status': 'running', 'at': 1}],
                       [{'phase': p, 'status': 'failed', 'at': 1} for p in ('preflight', 'drain')],
                       [{'phase': 'migrate', 'status': 'failed', 'at': 1}]):
            self.write(self.paths['journal'], {**self.journal, 'events': events})
            self.pins['journal'] = H.sha(self.paths['journal'].read_bytes())
            with self.subTest(events=events), self.assertRaises(ValueError):
                self.run_reconcile()
        self.host.preflight.assert_not_called()

    def test_hash_pins_envelope_and_previous_mismatch_refuse_before_host_calls(self):
        for key in ('active', 'journal', 'previous', 'request'):
            self.paths[key].write_bytes(self.original[key] + b' ')
            if key == 'request':
                self.write(self.paths[key], {**self.request, 'target': 26})
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.run_reconcile()
            self.paths[key].write_bytes(self.original[key])
        self.write(self.paths['active'], {**self.blocked, 'image_id': 'sha256:'+'0'*64})
        self.pins['active'] = H.sha(self.paths['active'].read_bytes())
        with self.assertRaises(ValueError):
            self.run_reconcile()
        self.host.preflight.assert_not_called()

    def test_enabled_gates_or_installed_source_drift_refuse(self):
        for cfg in ({**self.config, 'enabled': True},
                    {**self.config, 'runtime': {**self.config['runtime'], 'enabled': True}},
                    {**self.config, 'executor_sha256': '0'*64}):
            self.write(self.config_path, cfg)
            with self.subTest(config=cfg), self.assertRaises(ValueError):
                self.run_reconcile()
        self.unchanged()
        self.host.preflight.assert_not_called()

    def test_enabled_timer_refuses_and_existing_audit_is_never_overwritten(self):
        self.host.command.return_value = b'ActiveState=active\nUnitFileState=enabled\n'
        with self.assertRaises(ValueError):
            self.run_reconcile()
        self.unchanged()
        before = (self.state / 'reconcile-123-1/intent.json').read_bytes()
        with self.assertRaises(FileExistsError):
            self.run_reconcile()
        self.assertEqual((self.state / 'reconcile-123-1/intent.json').read_bytes(), before)

    def test_verification_receipt_failure_never_commits_active(self):
        save = H.save
        def fail_receipt(path, value):
            if path.name == 'verified.json':
                raise OSError('disk full')
            save(path, value)
        with patch.object(H, 'save', side_effect=fail_receipt), self.assertRaises(OSError):
            self.run_reconcile()
        self.unchanged()

    def test_active_commit_is_last_write_and_receipt_exists_before_it(self):
        save, writes = H.save, []
        def track(path, value):
            if path == self.paths['active']:
                self.assertTrue((self.state / 'reconcile-123-1/verified.json').exists())
            writes.append(path.name)
            save(path, value)
        with patch.object(H, 'save', side_effect=track):
            self.run_reconcile()
        self.assertEqual(writes, ['intent.json', 'verified.json', 'active.json'])

    def test_shared_release_lock_refuses_before_reconciliation(self):
        import fcntl
        argv = ['reconcile', '--request', '123-1']
        for key, value in self.pins.items():
            argv += ['--' + key + '-sha256', value]
        with open(self.root / 'lock', 'rb') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with patch.object(sys, 'argv', argv), patch.object(os, 'geteuid', return_value=0), self.assertRaises(BlockingIOError):
                R.main()
        self.unchanged()
        self.host.preflight.assert_not_called()


class ObservationTests(unittest.TestCase):
    def setUp(self):
        self.previous = {'image_id': 'old-id', 'digest': 'sha256:'+'a'*64}
        self.obj = {'Id': 'c'*64, 'Image': 'old-id', 'RestartCount': 0, 'Mounts': [],
                    'Config': {'Image': H.REGISTRY + '@' + self.previous['digest'], 'Env': ['SECRET=DO_NOT_LOG']},
                    'HostConfig': {'RestartPolicy': {'Name': 'no'}},
                    'State': {'Running': True, 'Health': {'Status': 'healthy'}, 'StartedAt': 'fixture'}}
        self.host = Mock()
        self.host.command.return_value = b''
        self.host.container.return_value = self.obj

    def observe(self):
        with patch.object(Path, 'read_text', return_value='boot'):
            return R.observation(self.host, self.previous, '123-1')

    def test_observation_is_read_only_and_excludes_environment(self):
        result = self.observe()
        self.assertNotIn('DO_NOT_LOG', json.dumps(result))
        self.assertEqual(result['boot_id'], 'boot')
        self.host.command.assert_called_once_with(
            ['docker', 'ps', '-q', '--filter', 'label=hobbytree.main-release=123-1'])
        self.assertEqual(self.host.container.call_count, 2)

    def test_surviving_db_child_and_unhealthy_or_foreign_container_refuse(self):
        self.host.command.return_value = b'db-child\n'
        with self.assertRaises(ValueError):
            self.observe()
        self.host.command.return_value = b''
        for change in ({'Image': 'foreign'}, {'State': {**self.obj['State'], 'Running': False}},
                       {'HostConfig': {'RestartPolicy': {'Name': 'always'}}}):
            self.host.container.return_value = {**self.obj, **change}
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.observe()


if __name__ == '__main__':
    unittest.main()
