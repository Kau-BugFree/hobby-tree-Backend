"""Approval binding and live rollback evidence regressions; no AWS calls."""
import importlib.util
import json
from pathlib import Path
import tempfile
import os
import subprocess
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


H = load('release_v45_tests', ROOT / 'scripts/deploy/ec2-release.py')
G = load('gate_tests', ROOT / 'scripts/deploy/v45_gate.py')


class ApprovalTests(unittest.TestCase):
    def setUp(self):
        self.gate = G.Gate('a' * 32, H)
        self.release = Mock(previous=H.REPOSITORY + '@sha256:' + '1' * 64,
                            image=H.REPOSITORY + '@sha256:' + '2' * 64, revision='b' * 40)
        self.release.image_info.return_value = {'RepoDigests': [self.release.previous], 'Config': {
            'Labels': {'org.opencontainers.image.revision': G.LEGACY}}}
        self.config = {'database': {'host': 'test.rds.amazonaws.com', 'port': 5432,
                                   'dbname': 'hobbytree', 'identity': 'test-only-db-uuid'}, 'public_key': '/key'}
        self.release.compose_env = {'DB_URL': 'jdbc:postgresql://test.rds.amazonaws.com:5432/hobbytree?sslmode=verify-full'}
        self.proof = dict(format=1, approval_id='a' * 32, previous=self.release.previous,
                          candidate=self.release.image, previous_revision=G.LEGACY,
                          revision=self.release.revision, before_schema=G.BEFORE, after_schema=G.AFTER,
                          database=self.config['database'], issued_at=100, expires_at=2000,
                          validation_report_sha256='c' * 64)
        self.report = json.dumps(dict(revision=self.release.revision, candidate=self.release.image,
                                      migration_tests=9, failures=0, errors=0, skipped=0,
                                      docker_validation='success')).encode()
        self.proof['validation_report_sha256'] = H.sha(self.report)
        self.gate.read = lambda path: (self.report if str(path).endswith('.report.json') else
                                      json.dumps(self.config if path == G.CONFIG else self.proof).encode())

    def authorize(self, before=G.BEFORE, after=G.AFTER, signature=0):
        with patch.object(G.time, 'time', return_value=200), patch.object(
                G.subprocess, 'run', return_value=Mock(returncode=signature)):
            return self.gate.authorize(self.release, before, after)

    def test_valid_signed_context(self):
        self.assertEqual(self.authorize(), self.config)

    def test_signature_expiry_digest_revision_db_and_missing_report_are_rejected(self):
        for key, value in [('expires_at', 199), ('issued_at', 201), ('candidate', 'wrong'),
                           ('previous', 'wrong'), ('revision', 'wrong'), ('database', {}),
                           ('validation_report_sha256', ''), ('approval_id', 'b' * 32),
                           ('before_schema', 'bad'), ('after_schema', 'bad')]:
            original = self.proof[key]
            self.proof[key] = value
            with self.subTest(key=key), self.assertRaises(H.ReleaseError):
                self.authorize()
            self.proof[key] = original
        with self.assertRaises(H.ReleaseError):
            self.authorize(signature=1)

    def test_sql_change_or_extra_migration_and_wrong_rollback_image_rejected(self):
        for before, after in [('changed', G.AFTER), (G.BEFORE, 'v6-or-edited-sql')]:
            with self.assertRaises(H.ReleaseError):
                self.authorize(before, after)
        self.release.image_info.return_value['Config']['Labels']['org.opencontainers.image.revision'] = 'd' * 40
        with self.assertRaises(H.ReleaseError):
            self.authorize()

    def test_observed_db_must_be_the_actual_backend_target(self):
        for url in ['jdbc:postgresql://different.rds.amazonaws.com/hobbytree?sslmode=verify-full',
                    'jdbc:postgresql://test.rds.amazonaws.com/other?sslmode=verify-full',
                    'jdbc:postgresql://test.rds.amazonaws.com/hobbytree?sslmode=require',
                    'jdbc:postgresql://test.rds.amazonaws.com/hobbytree?sslmode=verify-full&currentSchema=other']:
            self.release.compose_env['DB_URL'] = url
            with self.subTest(url=url), self.assertRaises(H.ReleaseError):
                self.authorize()

    def test_history_identity_guard_enum_and_lost_connection_fail_closed(self):
        self.gate.database = self.config['database']
        good = [('hobbytree',), [('test-only-db-uuid',)], G.HISTORY, [(True, G.GUARD)], []]
        def check(values):
            connection = Mock(closed=False)
            connection.info.transaction_status = 2
            connection.execute.side_effect = [Mock(fetchone=Mock(return_value=(3,))), Mock(fetchone=Mock(return_value=values[0]))] + [
                Mock(fetchall=Mock(return_value=v)) for v in values[1:]]
            self.gate.connection = connection
            self.gate.check()
        check(good)
        for index, value in [(0, ('wrong',)), (1, [('clone',)]), (2, G.HISTORY[:-1]),
                             (2, [*G.HISTORY[:-1], ('5', G.HISTORY[-1][1], 0, True)]),
                             (3, []), (3, [(False, G.GUARD)]), (4, [(1,)])]:
            changed = list(good)
            changed[index] = value
            with self.subTest(index=index), self.assertRaises(H.ReleaseError):
                check(changed)
        self.gate.connection.closed = True
        with self.assertRaises(H.ReleaseError):
            self.gate.check()

    @unittest.skipUnless(hasattr(os, 'O_NOFOLLOW'), 'POSIX durable marker test')
    def test_approval_is_durably_consumed_once(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(H, 'STATE', Path(folder)):
            self.release.request_id = '123-1'
            self.release.record = {}
            self.gate.consume(self.release)
            self.assertEqual(self.release.record['v45_approval'], 'a'*32)
            with self.assertRaises(FileExistsError):
                self.gate.consume(self.release)
        # O_EXCL must also reject a racing request, rather than only sequential replay.
        from concurrent.futures import ThreadPoolExecutor
        with tempfile.TemporaryDirectory() as folder, patch.object(H, 'STATE', Path(folder)):
            def attempt():
                try:
                    self.gate.consume(self.release)
                    return 'used'
                except FileExistsError:
                    return 'rejected'
            with ThreadPoolExecutor(max_workers=2) as workers:
                self.assertCountEqual(list(workers.map(lambda _: attempt(), range(2))), ['used', 'rejected'])

    def test_bad_ids_never_reach_filesystem(self):
        for value in ['../id', '$(id)', '', 'A' * 32]:
            with self.assertRaises(H.ReleaseError):
                G.Gate(value, H)

    @unittest.skipUnless(Path('/usr/bin/openssl').is_file(), 'Linux OpenSSL signature test')
    def test_real_signature_and_tampered_payload(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            key, public = root / 'private.pem', root / 'public.pem'
            body, signature = root / ('a'*32 + '.json'), root / ('a'*32 + '.sig')
            self.config['public_key'] = str(public)
            body.write_text(json.dumps(self.proof))
            (root / ('a'*32 + '.report.json')).write_bytes(self.report)
            for command in [
                ['genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(key)],
                ['pkey', '-in', str(key), '-pubout', '-out', str(public)],
                ['dgst', '-sha256', '-sign', str(key), '-out', str(signature), str(body)],
            ]:
                subprocess.run(['/usr/bin/openssl', *command], check=True, capture_output=True, timeout=15)
            self.release.cmd.env = {'PATH': '/usr/bin:/bin'}
            self.gate.read = lambda p: json.dumps(self.config).encode() if p == G.CONFIG else p.read_bytes()
            with patch.object(G, 'APPROVALS', root), patch.object(G.time, 'time', return_value=200):
                self.gate.authorize(self.release, G.BEFORE, G.AFTER)
                body.write_text(json.dumps({**self.proof, 'candidate': 'tampered'}))
                with self.assertRaises(H.ReleaseError):
                    self.gate.authorize(self.release, G.BEFORE, G.AFTER)


class RollbackTests(unittest.TestCase):
    def test_incompatible_db_prevents_backend_recreation(self):
        release = H.Deployment(Mock(), '/unused', 'sha256:'+'2'*64, 'sha256:'+'1'*64, 'a'*40, '123-1')
        release.migration_gate = Mock()
        release.migration_gate.check.side_effect = H.ReleaseError('incompatible DB')
        release.compose = Mock()
        with self.assertRaises(H.ReleaseError):
            release.start_backend()
        release.compose.assert_not_called()
