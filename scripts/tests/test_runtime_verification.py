from contextlib import contextmanager
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

PATH = Path(__file__).resolve().parents[1] / 'deploy/runtime_verification.py'
spec = importlib.util.spec_from_file_location('runtime_verification_test', PATH)
V = importlib.util.module_from_spec(spec)
spec.loader.exec_module(V)
BOOT = 'a' * 32


def state(active='inactive', *, start=100, end=200, outcome='RUNTIME_HEALTHY'):
    return {'boot_id': BOOT, 'properties': {'Type': 'oneshot', 'LoadState': 'loaded',
            'UnitFileState': 'enabled', 'ActiveState': active, 'SubState': 'dead',
            'Result': 'success', 'ExecMainStatus': '0', 'ExecMainStartTimestampMonotonic': str(start),
            'ExecMainExitTimestampMonotonic': str(end)}, 'journal': [{'_BOOT_ID': BOOT,
            '_SYSTEMD_UNIT': V.SERVICE, 'MESSAGE': outcome, '__MONOTONIC_TIMESTAMP': str((start + end) // 2)}]}


class VerificationTests(unittest.TestCase):
    def setUp(self):
        self.now = 0
        self.locked = False
        self.lock_attempts = 0

    @contextmanager
    def lock(self):
        self.lock_attempts += 1
        self.assertFalse(self.locked)
        self.locked = True
        try:
            yield True
        finally:
            self.locked = False

    def sleep(self, seconds):
        self.assertFalse(self.locked, 'Never wait while holding the deployment lock')
        self.now += seconds

    def verify(self, rows, inspect=None, **kwargs):
        read = Mock(side_effect=rows) if isinstance(rows, list) else rows
        self.inspect = inspect or Mock(return_value='report')
        return V.verify_oneshot(read, self.lock, self.inspect, clock=lambda: self.now,
                                sleep=self.sleep, timeout=3, interval=1, **kwargs)

    def test_completed_success_is_bracketed_by_same_invocation(self):
        result = self.verify([state(), state(), state()])
        self.assertEqual(result['inspection'], 'report')
        self.assertEqual(result['invocation']['outcome'], 'RUNTIME_HEALTHY')
        self.inspect.assert_called_once()

    def test_transitions_wait_without_taking_lock(self):
        result = self.verify([state('activating'), state('deactivating'), state(), state(), state()])
        self.assertEqual(result['inspection'], 'report')
        self.assertEqual(self.lock_attempts, 1)
        self.assertEqual(self.now, 2)

    def test_timer_starts_after_first_read_release_and_retry(self):
        self.verify([state(), state('activating'), state(start=300, end=400),
                     state(start=300, end=400), state(start=300, end=400)])
        self.assertEqual(self.now, 1)
        self.inspect.assert_called_once()

    def test_completed_invocation_changes_before_lock_rechecks(self):
        new = state(start=300, end=400)
        self.verify([state(), new, new, new, new])
        self.inspect.assert_called_once()

    def test_invocation_changes_during_inspection_discards_result(self):
        new = state(start=300, end=400)
        inspect = Mock(side_effect=['discard', 'stable'])
        result = self.verify([state(), state(), new, new, new, new], inspect)
        self.assertEqual(result['inspection'], 'stable')
        self.assertEqual(inspect.call_count, 2)

    def test_busy_zero_exit_never_counts_as_success(self):
        with self.assertRaises(TimeoutError):
            self.verify(lambda: state(outcome='RUNTIME_BUSY'))
        self.inspect.assert_not_called()
        self.assertEqual(self.lock_attempts, 0)

    def test_busy_awaits_a_fresh_healthy_invocation(self):
        good = state(start=300, end=400)
        self.verify([state(outcome='RUNTIME_BUSY'), good, good, good])
        self.assertEqual(self.now, 1)

    def test_journal_delivery_lag_waits(self):
        missing = state();missing['journal'] = []
        self.verify([missing, state(), state(), state()])
        self.assertEqual(self.now, 1)

    def test_previous_boot_or_invocation_cannot_supply_success(self):
        for change in ('boot', 'time', 'unit'):
            stale = state()
            row = stale['journal'][0]
            row[{'boot': '_BOOT_ID', 'time': '__MONOTONIC_TIMESTAMP', 'unit': '_SYSTEMD_UNIT'}[change]] = {
                'boot': 'b' * 32, 'time': '99', 'unit': 'another.service'}[change]
            with self.subTest(change=change):
                self.assertIsNone(V.completed(stale))

    def test_failure_or_rate_limit_is_not_ignored_even_with_zero_exit(self):
        for outcome in ('RUNTIME_RECOVERY_FAILED_CHECK_PROTECTED_JOURNAL', 'RUNTIME_RATE_LIMITED', 'RUNTIME_UNKNOWN'):
            with self.subTest(outcome=outcome), self.assertRaises(V.VerificationError):
                V.completed(state(outcome=outcome))

    def test_failed_or_wrong_unit_and_nonzero_exit_are_rejected(self):
        for changes in ({'ActiveState': 'failed'}, {'Result': 'exit-code'}, {'ExecMainStatus': '2'},
                        {'Type': 'simple'}, {'LoadState': 'not-found'}, {'UnitFileState': 'disabled'},
                        {'ActiveState': 'active'}, {'ExecMainExitTimestampMonotonic': '99'}):
            item = state();item['properties'].update(changes)
            with self.subTest(changes=changes), self.assertRaises(V.VerificationError):
                V.completed(item)

    def test_never_run_service_and_permanent_transition_time_out(self):
        for item in (state('activating'), state(start=0, end=0)):
            with self.subTest(item=item), self.assertRaises(TimeoutError):
                self.verify(lambda: item)

    def test_ambiguous_results_rejected(self):
        item = state();other = dict(item['journal'][0], MESSAGE='RUNTIME_BUSY');item['journal'].append(other)
        with self.assertRaises(V.VerificationError):V.completed(item)

    def test_recovery_is_accepted_only_when_explicitly_requested(self):
        with self.assertRaises(V.VerificationError):self.verify([state(outcome='RUNTIME_RECOVERED')])
        good = state(outcome='RUNTIME_RECOVERED')
        result = self.verify([good, good, good], accepted={'RUNTIME_RECOVERED'})
        self.assertEqual(result['invocation']['outcome'], 'RUNTIME_RECOVERED')

    def test_inspection_failure_propagates_and_unlocks(self):
        with self.assertRaisesRegex(ValueError, 'database-drift'):
            self.verify([state(), state()], Mock(side_effect=ValueError('database-drift')))
        self.assertFalse(self.locked)

    def test_actual_failure_after_inspection_aborts_without_retry(self):
        with self.assertRaises(V.VerificationError):
            self.verify([state(), state(), state('failed')])
        self.assertFalse(self.locked)
        self.assertEqual(self.now, 0)
        self.inspect.assert_called_once()

    def test_deadline_includes_inspection(self):
        def slow():self.now += 4
        with self.assertRaises(TimeoutError):self.verify([state(), state()], slow)
        self.assertFalse(self.locked)

    def test_lock_contention_waits_outside_lock(self):
        @contextmanager
        def busy():yield False
        with self.assertRaises(TimeoutError):
            V.verify_oneshot(lambda: state(), busy, Mock(), timeout=2, interval=1,
                             clock=lambda: self.now, sleep=self.sleep)

    def test_invalid_limits_cannot_create_unbounded_waits(self):
        for key in ('timeout', 'interval'):
            for value in (0, -1, float('nan'), float('inf'), True):
                with self.subTest(key=key, value=value), self.assertRaises(V.VerificationError):
                    V.verify_oneshot(Mock(), Mock(), Mock(), **{key: value})

    def test_read_only_adapter_queries_fixed_metadata(self):
        props = state()['properties']
        run = Mock(side_effect=[('\n'.join(k+'='+v for k,v in props.items())).encode(),
                                (json.dumps(state()['journal'][0])+'\n').encode()])
        self.assertEqual(V.systemd_snapshot(run, lambda: BOOT)['journal'], state()['journal'])
        self.assertEqual(run.call_args_list[0].args[0][:3], ['systemctl', 'show', V.SERVICE])
        self.assertEqual(run.call_args_list[1].args[0][:4], ['journalctl', '-b', '-u', V.SERVICE])

    def test_boot_change_during_snapshot_rejected(self):
        props = state('activating')['properties']
        run = Mock(return_value=('\n'.join(k+'='+v for k,v in props.items())).encode())
        with self.assertRaisesRegex(V.VerificationError, 'boot-changed'):
            V.systemd_snapshot(run, Mock(side_effect=[BOOT, 'b'*32]))

    @unittest.skipUnless(os.name == 'posix', 'Linux flock integration')
    def test_actual_flock_is_released_on_wait_and_on_inspection_error(self):
        import fcntl
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'lock';path.write_bytes(b'')
            def sleep(seconds):
                with path.open('rb') as stream:
                    fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.now += seconds
            read = Mock(side_effect=[state(), state('activating'), state(), state()])
            with self.assertRaises(ValueError):
                V.verify_oneshot(read, lambda: V.deployment_lock(path), Mock(side_effect=ValueError('drift')),
                                 clock=lambda: self.now, sleep=sleep, interval=1)
            with V.deployment_lock(path) as acquired:self.assertTrue(acquired)


if __name__ == '__main__':
    unittest.main(verbosity=2)
