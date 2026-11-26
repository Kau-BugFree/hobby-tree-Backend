import contextlib
from datetime import datetime, timezone
import hashlib
import io
import json
import multiprocessing
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, MagicMock

import entrypoint
import export_queue as queue
import import_export
import prepare_settings
import prepare_source


class Files(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def secret(self, name, value='x' * 64):
        path = self.root / name
        path.write_text(value)
        path.chmod(0o600)
        return path


class Secrets(Files):
    def setUp(self):
        super().setUp()
        self.secret('item_service_token')
        self.secret('item_db_password')

    def test_api_does_not_inherit_provider_or_plaintext_secrets(self):
        env = entrypoint.environment('api', {'SERVICE_TOKEN': 'bad', 'ADPICK_API_KEY': 'bad',
                                           'PGPASSWORD': 'bad', 'PGOPTIONS': 'bad'}, self.root)
        self.assertEqual(env['SERVICE_TOKEN'], 'x' * 64)
        self.assertNotIn('ADPICK_API_KEY', env)
        self.assertNotIn('PGOPTIONS', env)
        self.assertEqual(env['APP_ENV'], 'production')

    def test_permissions_symlink_and_length_fail(self):
        p = self.root / 'item_service_token'
        for value in ('short', 'x' * 129, 'x' * 64 + '\n\nsecret', 'x' * 64 + ' '):
            p.write_text(value)
            with self.assertRaises(ValueError):
                entrypoint.environment('api', {}, self.root)
        p.write_text('x' * 64)
        p.chmod(0o644)
        with self.assertRaises(ValueError):
            entrypoint.environment('api', {}, self.root)
        p.unlink()
        p.symlink_to(self.root / 'item_db_password')
        with self.assertRaises(OSError):
            entrypoint.environment('api', {}, self.root)

    def test_worker_requires_both_approvals_and_key(self):
        for env in ({}, {'ADPICK_STORAGE_APPROVED': 'true'}):
            with self.assertRaises(ValueError):
                entrypoint.environment('worker', env, self.root)
        self.secret('adpick_api_key')
        env = entrypoint.environment('worker', {'ADPICK_STORAGE_APPROVED': 'true',
                                                'ADPICK_FILTERING_APPROVED': 'true'}, self.root)
        self.assertEqual(env['ADPICK_API_KEY'], 'x' * 64)

    def test_actual_cli_redacts_failure(self):
        r = subprocess.run([sys.executable, str(Path(entrypoint.__file__)), 'api'],
                           env={**os.environ, 'SERVICE_TOKEN': 'SECRET_SENTINEL'},
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 1)
        self.assertNotIn('SECRET_SENTINEL', r.stdout + r.stderr)
        self.assertNotIn('Traceback', r.stderr)


def lock_child(directory, pipe):
    with queue.locked(directory):
        pipe.send('locked')
        pipe.recv()


class Queue(Files):
    def setUp(self):
        super().setUp()
        queue.initialize(self.root, 'synthetic-hobbytree', '11111111-1111-4111-8111-111111111111')

    def produce(self, rows):
        with patch.object(queue.export, 'read_snapshot', return_value=(datetime.now(timezone.utc), rows)):
            return queue.produce(self.root, 'test', lambda _: contextlib.nullcontext(object()))

    def test_failed_run_reserved_then_next_export_and_replay(self):
        def bad(_):
            raise RuntimeError('PASSWORD_DO_NOT_LOG')
        with self.assertRaises(RuntimeError):
            queue.produce(self.root, 'test', bad)
        self.assertEqual(json.loads((self.root / '1/receipt.json').read_text())['status'], 'FAILED')
        receipt = self.produce([(1, 2, 3, 1, 'reading')])
        self.assertEqual(receipt['sequence'], 2)
        self.assertEqual(queue.replay(self.root, 2), receipt)
        raw = (self.root / '2/export.json').read_bytes()
        self.assertEqual(json.loads(raw)['sourceSequence'], '2')
        (self.root / '2/export.json').write_bytes(raw + b' ')
        with self.assertRaises(ValueError):
            queue.replay(self.root, 2)

    def test_empty_holds_and_does_not_allow_replay(self):
        self.assertEqual(self.produce([])['status'], 'HOLD_EMPTY')
        with self.assertRaises(ValueError):
            queue.replay(self.root, 1)

    def test_second_process_cannot_export_while_locked(self):
        parent, child = multiprocessing.Pipe()
        process = multiprocessing.Process(target=lock_child, args=(self.root, child))
        process.start()
        try:
            self.assertTrue(parent.poll(10))
            self.assertEqual(parent.recv(), 'locked')
            with self.assertRaises(BlockingIOError):
                self.produce([])
        finally:
            parent.send('done')
            process.join(10)
        self.assertEqual(process.exitcode, 0)
        self.assertEqual(json.loads((self.root / 'state.json').read_text())['last'], 0)

    def test_state_reinit_and_symlink_fail(self):
        with self.assertRaises(ValueError):
            queue.initialize(self.root, 'other', '11111111-1111-4111-8111-111111111111')
        self.root.chmod(0o755)
        with self.assertRaises(ValueError):
            self.produce([])
        self.root.chmod(0o700)
        (self.root / 'state.json').unlink()
        (self.root / 'state.json').symlink_to('/etc/passwd')
        with self.assertRaises(ValueError):
            self.produce([])

    def test_crash_after_reservation_does_not_reuse_sequence(self):
        with patch.object(queue.export, 'publish_exclusive', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.produce([(1, 2, 3, 1, 'name')])
        self.assertEqual(self.produce([])['sequence'], 2)
        self.assertEqual(json.loads((self.root / '1/receipt.json').read_text())['status'], 'RESERVED')

    def test_real_cli_failure_has_no_trace_or_secret(self):
        result = subprocess.run([sys.executable, queue.__file__, '--directory', str(self.root),
                                 'export', '--service', 'invalid://SECRET'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertNotIn('SECRET', result.stdout + result.stderr)

    def test_connection_forces_tls_verification_and_read_only(self):
        driver = MagicMock()
        with patch.dict(sys.modules, {'psycopg': driver}), patch.object(
                queue.export, 'read_snapshot', return_value=(datetime.now(timezone.utc), [])):
            queue.produce(self.root, 'test')
        kwargs = driver.connect.call_args.kwargs
        self.assertEqual(kwargs['sslmode'], 'verify-full')
        self.assertEqual(kwargs['options'], '-c default_transaction_read_only=on')
        self.assertTrue(kwargs['autocommit'])


class Import(Files):
    def test_exact_bytes_symlink_limit_and_change(self):
        p = self.secret('export.json', '{}\n')
        digest = hashlib.sha256(p.read_bytes()).hexdigest()
        self.assertEqual(import_export.verified_bytes(p, digest), b'{}\n')
        p.write_text('{} \n')
        with self.assertRaises(ValueError):
            import_export.verified_bytes(p, digest)
        link = self.root / 'link'
        link.symlink_to(p)
        with self.assertRaises(ValueError):
            import_export.verified_bytes(link, digest)

    def test_preview_defaults_and_private_snapshot(self):
        p = self.secret('export.json', '{}\n')
        def fake(command, **kwargs):
            copied = Path(command[command.index('--file') + 1])
            self.assertNotEqual(copied, p)
            self.assertEqual(copied.read_bytes(), b'{}\n')
            self.assertNotIn('--apply', command)
            p.write_text('changed')
            return subprocess.CompletedProcess(command, 0, '{"status":"APPLY"}', '')
        with patch.object(import_export.subprocess, 'run', side_effect=fake), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(import_export.main(['--file', str(p), '--sha256', hashlib.sha256(p.read_bytes()).hexdigest(),
                                                 '--source-id', 'test', '--source-epoch', 'test']), 0)


class Settings(Files):
    def test_stt_staging_preserves_all_existing_values_and_disables(self):
        lines = []
        for prefix, port in [('recommendation', 8000), ('coach', 8003), ('item-recommendation', 8001)]:
            lines += [f'{prefix}.ai.enabled=true', f'{prefix}.ai.base-url=http://10.1.2.3:{port}',
                      f'{prefix}.ai.connect-timeout=2s', f'{prefix}.ai.request-timeout=10s']
            if prefix != 'recommendation':
                lines += [f'{prefix}.ai.service-token=' + 'x' * 64]
        original = '\n'.join(lines) + '\n'
        staged = prepare_settings.stage_stt(original)
        self.assertTrue(staged.startswith(original))
        self.assertTrue(staged.endswith('coach.stt.enabled=false\ncoach.stt.request-timeout=45s\n'))
        self.assertEqual(prepare_settings.stage_stt(staged), staged)
        with self.assertRaises(ValueError):
            prepare_settings.stage_stt(original + 'unknown=SECRET\n')

    def test_item_staging_correct_owners_no_overwrite(self):
        if os.geteuid() != 0:
            self.skipTest('Requires isolated Linux root')
        self.secret('item_service_token')
        self.secret('item_db_password', 'p' * 64)
        self.secret('catalog_admin_password', 'a' * 64)
        dest = self.root / 'staged'
        prepare_settings.stage_items(self.root, dest)
        for name, uid in [('item_service_token', 10001), ('item_db_password', 10001),
                          ('item_db_init_password', 999), ('catalog_admin_password', 999)]:
            self.assertEqual((dest / name).stat().st_uid, uid)
            self.assertEqual((dest / name).stat().st_mode & 0o777, 0o400)
        self.assertEqual((dest / 'item_db_password').read_bytes(), (dest / 'item_db_init_password').read_bytes())
        with self.assertRaises(FileExistsError):
            prepare_settings.stage_items(self.root, dest)


class PinnedSource(Files):
    def test_pinned_file_change_rejected_extra_env_not_copied(self):
        source = self.root / 'source'
        module = source / 'src/item-recommender'
        module.mkdir(parents=True)
        (module / 'code.py').write_bytes(b'pinned')
        (module / '.env').write_text('DO_NOT_COPY')
        lock = {'files': [{'sha256': hashlib.sha256(b'pinned').hexdigest(), 'path': 'code.py'}]}
        with patch.object(prepare_source.json, 'loads', return_value=lock):
            prepare_source.prepare(source, self.root / 'good')
            self.assertEqual(list((self.root / 'good').iterdir()), [self.root / 'good/code.py'])
            (module / 'code.py').write_bytes(b'changed')
            with self.assertRaises(ValueError):
                prepare_source.prepare(source, self.root / 'bad')
            self.assertFalse((self.root / 'bad').exists())


if __name__ == '__main__':
    unittest.main()
