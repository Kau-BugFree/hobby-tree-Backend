"""No AWS: exercise cold-start orchestration, durable budgets and real Linux secret files."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


R = load('runtime_test', 'scripts/deploy/main_runtime.py')
F = load('fetch_test', 'scripts/fetch-ec2-secrets.py')


class RuntimeTests(unittest.TestCase):
    def adapter(self):
        adapter = Mock()
        adapter.healthy.return_value = False
        adapter.attempt.return_value = True
        return adapter

    def test_cold_boot_order_and_no_migration_or_unfence(self):
        adapter, record = self.adapter(), Mock()
        self.assertEqual(R.recover(adapter, {'status': 'complete'}, record), 'recovered')
        self.assertEqual([c.args[0] for c in adapter.perform.call_args_list],
            ['isolate', 'stop', 'secrets', 'database', 'redis', 'backend', 'verify', 'publish'])

    def test_blocked_deployment_and_drift_never_start_or_fetch_secrets(self):
        for status in ('blocked', 'running', 'failed'):
            adapter = self.adapter()
            with self.assertRaises(ValueError):
                R.recover(adapter, {'status': status}, Mock())
            adapter.preflight.assert_not_called()
        adapter = self.adapter()
        adapter.preflight.side_effect = ValueError('configuration drift')
        with self.assertRaises(ValueError):
            R.recover(adapter, {'status': 'complete'}, Mock())
        adapter.perform.assert_not_called()
        adapter.contain.assert_not_called()

    def test_healthy_instance_is_not_recreated_or_rotated(self):
        adapter = self.adapter()
        adapter.healthy.return_value = True
        self.assertEqual(R.recover(adapter, {'status': 'complete'}, Mock()), 'healthy')
        adapter.perform.assert_not_called()
        adapter.attempt.assert_not_called()

    def test_every_recovery_failure_contains_and_preserves_failed_phase(self):
        phases = ['isolate', 'stop', 'secrets', 'database', 'redis', 'backend', 'verify', 'publish']
        for failed in phases:
            adapter, record = self.adapter(), Mock()
            adapter.perform.side_effect = lambda p: (_ for _ in ()).throw(ValueError()) if p == failed else None
            with self.subTest(failed=failed), self.assertRaises(ValueError):
                R.recover(adapter, {'status': 'complete'}, record)
            adapter.contain.assert_called_once()
            record.assert_any_call(failed, 'failed')
            self.assertEqual([c.args[0] for c in adapter.perform.call_args_list], phases[:phases.index(failed)+1])

    def test_attempt_budget_blocks_restart_storm_without_taking_down_healthy_app(self):
        adapter = self.adapter()
        adapter.attempt.return_value = False
        self.assertEqual(R.recover(adapter, {'status': 'complete'}, Mock()), 'rate_limited')
        adapter.perform.assert_not_called()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'main-release').mkdir()
            host = Mock(root=root, active={'digest': 'a'})
            host.save.side_effect = lambda path, value: path.write_text(json.dumps(value))
            runtime = R.Runtime(host, Mock())
            with patch.object(R, 'protected', side_effect=lambda p: Path(p)), patch.object(R.time, 'time', return_value=1000):
                self.assertTrue(runtime.attempt())
                self.assertTrue(runtime.attempt())
                self.assertTrue(runtime.attempt())
                self.assertFalse(runtime.attempt())
            with patch.object(R, 'protected', side_effect=lambda p: Path(p)), patch.object(R.time, 'time', return_value=1601):
                self.assertTrue(runtime.attempt())

    def test_secret_validation_failure_never_writes_any_credentials(self):
        host, fetch = Mock(config={'app_user': 'app', 'migration_user': 'migration'}), Mock()
        fetch.read_ai_credentials.return_value = {}
        fetch.read_credentials.side_effect = [{'DB_USERNAME': 'app', 'DB_PASSWORD': 'synthetic'}, ValueError()]
        fetch.read_auth_credentials.return_value = {'REDIS_PASSWORD': 'synthetic'}
        runtime = R.Runtime(host, fetch)
        runtime.runtime = {'app_secret': 'a', 'auth_secret': 'b', 'migration_secret': 'c'}
        runtime.app = '/run/hobbytree/secrets'
        with self.assertRaises(ValueError):
            runtime.prepare()
        fetch.write_credentials.assert_not_called()
        host.command.assert_not_called()

    def test_database_phase_is_exact_read_only_cli_and_keeps_baseline(self):
        host = Mock(config={})
        runtime = R.Runtime(host, Mock())
        runtime.perform('database')
        host.database.assert_called_once_with('runtime-check')

    def test_interrupted_recovery_and_unregistered_alb_are_not_healthy(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'main-release').mkdir()
            host = Mock(root=root, config={}, active={'request': '123-1', 'digest': 'digest'})
            runtime = R.Runtime(host, Mock())
            runtime.previous = runtime.redis_before = {'State': {'Running': True, 'Health': {'Status': 'healthy'}}}
            runtime.app, runtime.redis_dir = 'app', 'redis'
            path = root / 'main-release/runtime-status.json'
            for phase in ('backend', 'verify', 'publish', 'containment'):
                path.write_text(json.dumps({'release': '123-1', 'digest': 'digest', 'phase': phase, 'status': 'running'}))
                with patch.object(R, 'protected', side_effect=lambda p: Path(p)):
                    self.assertFalse(runtime.healthy())
            path.unlink()
            host.target.return_value = {'TargetHealthDescriptions': [{'TargetHealth': {'State': 'unused'}}]}
            self.assertFalse(runtime.healthy())
            host.target.return_value = {'TargetHealthDescriptions': [{'TargetHealth': {'State': 'healthy'}}]}
            self.assertTrue(runtime.healthy())
            host.probes.assert_called_with('https://api.hobbytreeapp.com')
            host.target.side_effect = ValueError('control plane unavailable')
            with self.assertRaises(ValueError):
                runtime.healthy()

    def test_containment_attempts_every_barrier_even_if_alb_or_journal_fails(self):
        host = Mock(config={})
        runtime = R.Runtime(host, Mock())
        runtime.perform = Mock(side_effect=ValueError('ALB unavailable'))
        runtime.stop_owned = Mock()
        runtime.record = Mock(side_effect=OSError('journal unavailable'))
        host.stop_database.side_effect = ValueError('DB container cleanup failed')
        with self.assertRaises(ValueError):
            runtime.contain()
        host.stop_database.assert_called_once()
        runtime.perform.assert_called_once_with('isolate')
        runtime.stop_owned.assert_called_once()

    def test_disabled_or_foreign_secret_config_rejected(self):
        prefix = 'arn:aws:secretsmanager:ap-northeast-2:494915081306:secret:'
        settings = {'enabled': True, 'app_secret': prefix+'app', 'auth_secret': prefix+'auth', 'migration_secret': prefix+'migration'}
        R.settings({'runtime': settings})
        for changes in ({'enabled': False}, {'app_secret': 'arbitrary'}, {'migration_secret': prefix+'app'}):
            with self.assertRaises(ValueError):
                R.settings({'runtime': {**settings, **changes}})

    def test_units_do_not_launch_compose_or_fetch_outside_shared_lock(self):
        service = (ROOT / 'deploy/main-release/hobbytree-main-runtime.service').read_text()
        timer = (ROOT / 'deploy/main-release/hobbytree-main-runtime.timer').read_text()
        self.assertIn('After=docker.service network-online.target', service)
        self.assertIn('main_runtime.py', service)
        self.assertNotIn('ExecStartPre=', service)
        self.assertNotIn('docker compose', service)
        self.assertIn('OnUnitInactiveSec=60s', timer)


@unittest.skipUnless(os.name == 'posix' and os.geteuid() == 0, 'Linux root isolated filesystem')
class ColdSecretFilesTests(unittest.TestCase):
    def test_tmpfs_loss_recreates_ownership_files_and_redis_acl_without_stale_fallback(self):
        base = Path('/run/hobbytree')
        base.mkdir(parents=True, exist_ok=True)
        directory = Path(tempfile.mkdtemp(prefix='test-runtime-', dir=base))
        try:
            app, redis, migration = (directory / x for x in ('app', 'redis', 'migration'))
            credentials = {'DB_USERNAME': 'runtime_app', 'DB_PASSWORD': 'isolated-db-only',
                'JWT_SECRET': 'YWJj'*24, 'REDIS_PASSWORD': 'isolated-redis-password-only-0123456789'}
            import hashlib
            redis_values = {'REDIS_PASSWORD': credentials['REDIS_PASSWORD'], 'users.acl': 'user default on #' + hashlib.sha256(credentials['REDIS_PASSWORD'].encode()).hexdigest() + ' ~auth:* +@all\n'}
            for _ in range(2):
                F.write_credentials(str(redis), redis_values, uid=999)
                F.write_credentials(str(app), credentials)
                F.write_credentials(str(migration), {'DB_USERNAME': 'runtime_migration', 'DB_PASSWORD': 'isolated-migration-only'})
                F.VALIDATOR.validate_secret_files(app, redis)
                self.assertEqual((app / 'JWT_SECRET').stat().st_uid, 10001)
                self.assertEqual((redis / 'users.acl').stat().st_uid, 999)
                self.assertEqual((migration / 'DB_PASSWORD').stat().st_mode & 0o777, 0o400)
                # Simulate /run loss; only this randomly named test-owned tree is removed.
                for path in (app, redis, migration):
                    shutil.rmtree(path)
        finally:
            shutil.rmtree(directory)
