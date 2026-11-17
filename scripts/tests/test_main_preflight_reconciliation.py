"""AWS-free regression tests; real JAR bytes and durable filesystem evidence."""
import copy
import importlib.util
import json
import os
import subprocess
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
        self.host.diagnostics.return_value = {'check': 'database-marker', 'database': None}
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

    def fail_first(self):
        self.host.preflight.side_effect = ValueError('DO_NOT_LOG_SECRET')
        with self.assertRaises(ValueError):
            self.run_reconcile()
        self.host.preflight.side_effect = None
        self.audit = self.state / 'reconcile-123-1'
        self.digest = R.failed_chain('123-1', 1, self.original, self.previous)

    def retry(self, attempt=2, digest=None):
        R.reconcile('123-1', self.pins, attempt, digest or self.digest)

    def test_failed_attempt_can_retry_only_in_new_directory_with_pinned_chain(self):
        self.fail_first()
        preserved = {p.relative_to(self.audit): p.read_bytes() for p in self.audit.rglob('*') if p.is_file()}
        self.retry()
        for path, raw in preserved.items():
            self.assertEqual((self.audit / path).read_bytes(), raw)
        self.assertEqual(R.audit_hash(self.audit), self.digest)
        self.assertEqual(self.paths['active'].read_bytes(), self.original['previous'])
        intent = json.loads((self.state / 'reconcile-123-1-attempt-2/intent.json').read_bytes())
        self.assertEqual(intent['prior_audit_sha256'], self.digest)
        self.assertEqual(intent['attempt'], 2)
        self.host.perform.assert_not_called()

    def test_legacy_failed_receipt_is_accepted_with_explicit_hash(self):
        self.fail_first()
        intent = json.loads((self.audit / 'intent.json').read_bytes())
        del intent['attempt'], intent['prior_audit_sha256']
        self.write(self.audit / 'intent.json', intent)
        self.write(self.audit / 'failed.json', {'status': 'blocked', 'error_type': 'ValueError', 'at': 1})
        self.digest = R.failed_chain('123-1', 1, self.original, self.previous)
        self.retry()

    def test_missing_wrong_hash_skipped_attempt_and_existing_attempt_refuse(self):
        self.fail_first()
        for attempt, digest in ((2, None), (2, '0'*64), (3, self.digest), (11, self.digest), (True, None)):
            with self.subTest(attempt=attempt, digest=digest), self.assertRaises((ValueError, FileNotFoundError)):
                R.reconcile('123-1', self.pins, attempt, digest)
        self.host.probes.side_effect = ValueError('failed again')
        with self.assertRaises(ValueError):
            self.retry()
        with self.assertRaises(FileExistsError):
            self.retry()
        self.unchanged()

    def test_multiple_failed_attempts_preserve_chain_and_detect_ancestor_tamper(self):
        self.fail_first()
        self.host.probes.side_effect = ValueError('failed again')
        with self.assertRaises(ValueError):
            self.retry()
        digest2 = R.failed_chain('123-1', 2, self.original, self.previous)
        self.host.probes.side_effect = None
        self.retry(3, digest2)
        self.assertTrue((self.state / 'reconcile-123-1-attempt-3/verified.json').exists())
        self.assertEqual(R.audit_hash(self.audit), self.digest)
        self.assertEqual(R.failed_chain('123-1', 2, self.original, self.previous), digest2)
        (self.audit / 'tampered').write_text('tamper')
        with self.assertRaises(ValueError):
            R.failed_chain('123-1', 2, self.original, self.previous)

    def test_partial_verified_or_credential_bearing_attempt_refuses(self):
        self.fail_first()
        for relative in ('verified.json', 'docker/config.json'):
            path = self.audit / relative
            path.write_text('{}')
            with self.subTest(relative=relative), self.assertRaises(ValueError):
                R.failed_chain('123-1', 1, self.original, self.previous)
            path.unlink()
        (self.audit / 'failed.json').unlink()
        with self.assertRaises(FileNotFoundError):
            R.failed_chain('123-1', 1, self.original, self.previous)
        self.unchanged()

    def test_prior_request_or_input_hash_mismatch_refuses(self):
        self.fail_first()
        original = json.loads((self.audit / 'intent.json').read_bytes())
        for change in ({'request': '999-1'}, {'input_sha256': {}}, {'restored_active_sha256': '0'*64}):
            self.write(self.audit / 'intent.json', {**original, **change})
            with self.subTest(change=change), self.assertRaises(ValueError):
                R.failed_chain('123-1', 1, self.original, self.previous)
        self.unchanged()

    def test_prior_bytes_or_directory_inventory_changed_during_retry_never_commit(self):
        self.fail_first()
        self.host.preflight.side_effect = lambda *_: (self.audit / 'added').write_text('changed')
        with self.assertRaises(ValueError):
            self.retry()
        self.unchanged()
        failure = json.loads((self.state / 'reconcile-123-1-attempt-2/failed.json').read_bytes())
        self.assertEqual(failure['stage'], 'final-evidence')

    def test_root_permission_change_invalidates_approved_audit_hash(self):
        self.fail_first()
        self.audit.chmod(0o755)
        self.assertNotEqual(R.audit_hash(self.audit), self.digest)
        with self.assertRaises(ValueError):
            self.retry()
        self.assertFalse((self.state / 'reconcile-123-1-attempt-2').exists())
        self.unchanged()

    def test_root_permission_change_during_retry_refuses_active_restore(self):
        self.fail_first()
        self.host.preflight.side_effect = lambda *_: self.audit.chmod(0o755)
        with self.assertRaises(ValueError):
            self.retry()
        self.unchanged()
        failure = json.loads((self.state / 'reconcile-123-1-attempt-2/failed.json').read_bytes())
        self.assertEqual(failure['stage'], 'final-evidence')

    def test_symlinks_and_hardlinks_are_not_followed(self):
        self.fail_first()
        link = self.audit / 'link'
        link.symlink_to(self.config_path)
        with self.assertRaises(ValueError):
            R.audit_hash(self.audit)
        link.unlink()
        os.link(self.config_path, link)
        with self.assertRaises(ValueError):
            R.audit_hash(self.audit)

    def test_inspect_cli_is_read_only_and_does_not_start_host(self):
        self.fail_first()
        self.host.reset_mock()
        argv = ['reconcile', '--request', '123-1', '--inspect-attempt', '1']
        for key, value in self.pins.items():
            argv += ['--' + key + '-sha256', value]
        with patch.object(sys, 'argv', argv), patch.object(os, 'geteuid', return_value=0), patch('builtins.print') as out:
            R.main()
        self.assertEqual(json.loads(out.call_args.args[0])['audit_sha256'], self.digest)
        self.unchanged()
        self.host.preflight.assert_not_called()
        self.assertFalse((self.state / 'reconcile-123-1-attempt-2').exists())

    def test_each_failure_stage_is_safe_and_distinguishable(self):
        self.fail_first()
        failure = json.loads((self.audit / 'failed.json').read_bytes())
        self.assertEqual(failure['stage'], 'preflight')
        self.assertEqual(failure['host']['check'], 'database-marker')
        self.assertNotIn('DO_NOT_LOG_SECRET', json.dumps(failure))
        self.host.wait_target.side_effect = ValueError('DO_NOT_LOG_SECRET')
        with self.assertRaises(ValueError):
            self.retry()
        failure = json.loads((self.state / 'reconcile-123-1-attempt-2/failed.json').read_bytes())
        self.assertEqual(failure['stage'], 'target-health')


class DatabaseDiagnosticsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.host = H.Host({'db_host': 'fixture', 'app_user': 'app', 'migration_user': 'migration',
                            'database_owner': 'owner', 'privileged_logins': ['owner'], 'ca_file': '/fixture'},
                           Path(self.temp.name))
        self.host.after = [{'name': 'V1__fixture.sql', 'crc': 1}]
        self.host.active = {'migrations': []}
        self.host.request = {'request': '123-1', 'digest': 'fixture'}
        self.host.command = Mock(return_value=b'RELEASE_PASS=before\n')
        self.host.stop_database = Mock()
        lstat = Path.lstat
        def info(path, *args, **kwargs):
            if str(path).replace('\\', '/').startswith('/run/hobbytree/migration-secrets/'):
                return os.stat_result((0o100400, 1, 1, 1, 10001, 10001, 1, 1, 1, 1))
            return lstat(path, *args, **kwargs)
        for patcher in (patch.object(H, 'protected', side_effect=Path), patch.object(Path, 'lstat', info)):
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_success_preserves_strict_marker_and_records_only_booleans(self):
        self.host.database('before')
        self.assertEqual(self.host.database_result, {'command': 'success', 'marker_present': True,
                                                   'marker_final': True, 'cleanup': 'success'})
        self.host.stop_database.assert_called_once()

    def test_trailing_output_does_not_weaken_marker_guard(self):
        self.host.command.return_value = b'RELEASE_PASS=before\nDO_NOT_LOG_SECRET\n'
        with self.assertRaises(ValueError):
            self.host.database('before')
        self.assertEqual(self.host.check, 'database-marker')
        self.assertTrue(self.host.database_result['marker_present'])
        self.assertFalse(self.host.database_result['marker_final'])
        self.assertNotIn('DO_NOT_LOG_SECRET', json.dumps(self.host.diagnostics()))

    def test_failed_command_and_timeout_are_distinct_without_output_or_arguments(self):
        for error in (subprocess.CalledProcessError(1, ['secret'], output=b'secret', stderr=b'secret'),
                      subprocess.TimeoutExpired(['secret'], 1, output=b'secret', stderr=b'secret')):
            self.host.command.side_effect = error
            with self.subTest(error=type(error)), self.assertRaises(type(error)):
                self.host.database('before')
            self.assertEqual(self.host.check, 'database-run')
            self.assertEqual(self.host.database_result['error_type'], type(error).__name__)
            self.assertNotIn('secret', json.dumps(self.host.diagnostics()))

    def test_cleanup_failure_does_not_mask_primary_failure(self):
        primary = subprocess.CalledProcessError(1, ['secret'])
        self.host.command.side_effect = primary
        self.host.stop_database.side_effect = ValueError('secret')
        with self.assertRaises(subprocess.CalledProcessError) as raised:
            self.host.database('before')
        self.assertIs(raised.exception, primary)
        self.assertEqual(self.host.database_result['cleanup'], 'ValueError')
        self.assertEqual(self.host.check, 'database-run')

    def test_cleanup_only_failure_is_not_success(self):
        self.host.stop_database.side_effect = ValueError('secret')
        with self.assertRaises(ValueError):
            self.host.database('before')
        self.assertEqual(self.host.check, 'database-cleanup')

    def test_diagnostic_write_failure_still_cleans_up_checker(self):
        def fail(name):
            if name == 'database-marker':
                raise OSError('secret')
        self.host.on_check = fail
        with self.assertRaises(OSError):
            self.host.database('before')
        self.host.stop_database.assert_called_once()

    def test_unapproved_checkpoint_or_exception_name_never_exposes_data(self):
        with self.assertRaises(ValueError):
            self.host.checkpoint('DO_NOT_LOG_SECRET')
        error = type('DO_NOT_LOG_SECRET', (Exception,), {})()
        self.assertEqual(H.failure_kind(error), 'Exception')


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
        self.bind_mount = {'Type': 'bind', 'Source': '/etc/fixture', 'Destination': '/fixture',
                           'Mode': 'ro', 'RW': False, 'Propagation': 'rprivate'}
        self.volume_mount = {'Type': 'volume', 'Name': 'data', 'Source': '/var/lib/docker/volumes/data/_data',
                             'Destination': '/data', 'Driver': 'local', 'Mode': 'rw', 'RW': True}

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

    def test_mount_order_does_not_change_observation(self):
        self.obj['Mounts'] = [self.bind_mount, self.volume_mount, self.bind_mount]
        before = self.observe()
        self.assertEqual(self.obj['Mounts'], [self.bind_mount, self.volume_mount, self.bind_mount])
        self.obj['Mounts'] = [self.volume_mount, self.bind_mount, self.bind_mount]
        self.assertEqual(self.observe(), before)

    def test_mount_changes_alter_observation_even_when_reordered(self):
        self.obj['Mounts'] = [self.bind_mount, self.volume_mount, self.bind_mount]
        before = self.observe()
        for change in ({'Source': '/other'}, {'Destination': '/other'}, {'RW': True},
                       {'Mode': 'rw'}, {'Propagation': 'shared'}, {'ExtraField': 'value'}):
            with self.subTest(change=change):
                self.obj['Mounts'] = [self.volume_mount, {**self.bind_mount, **change}, self.bind_mount]
                self.assertNotEqual(self.observe(), before)
        for mounts in ([self.volume_mount, self.bind_mount],
                       [self.volume_mount, self.bind_mount, self.bind_mount, self.bind_mount]):
            self.obj['Mounts'] = mounts
            self.assertNotEqual(self.observe(), before)
        self.obj['Mounts'] = {}
        with self.assertRaisesRegex(ValueError, 'Invalid Docker mount list'):
            self.observe()

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
