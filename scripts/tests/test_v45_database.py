"""Opt-in disposable localhost PG17 regression for the server-enforced rollback barrier."""
import os
import re
import unittest
import uuid
from unittest.mock import Mock
from pathlib import Path
from test_v45_gate import G, H, ROOT

def migration_version(path):
    # Plain lexicographic sort on the filename breaks once a double-digit version exists
    # ("V10" < "V2" as strings) - sort on the numeric Vxx prefix instead, matching Flyway's
    # own ordering and scripts/ci/init-database.sh's "sort -V".
    return int(re.match(r'V(\d+)', path.name).group(1))


@unittest.skipUnless(os.environ.get('V45_TEST_DSN'), 'isolated PG17 not configured')
class DatabaseTests(unittest.TestCase):
    def test_live_history_barrier_and_ddl_lock(self):
        import psycopg
        dsn = os.environ['V45_TEST_DSN']
        self.assertRegex(dsn, r'^postgresql://127\.0\.0\.1:[0-9]+/hobbytree_migration_ci$')
        admin = psycopg.connect(dsn, autocommit=True)
        name = 'v45_case_' + uuid.uuid4().hex
        db = None
        gate = G.Gate('a'*32, H)
        try:
            admin.execute('CREATE DATABASE ' + name)
            db = psycopg.connect(dsn.replace('/hobbytree_migration_ci', '/' + name), autocommit=True)
            self.assertTrue(db.info.server_version // 10000 == 17)
            for path in sorted(
                (ROOT / 'src/main/resources/db/migration').glob('V*.sql'), key=migration_version
            ):
                db.execute(path.read_text(encoding='utf-8'))
            db.execute('CREATE TABLE flyway_schema_history(installed_rank int, version text, script text, checksum int, success bool)')
            for rank, row in enumerate(G.HISTORY, 1):
                db.execute('INSERT INTO flyway_schema_history VALUES (%s,%s,%s,%s,%s)', (rank, *row))
            db.execute("CREATE SCHEMA deployment_control; CREATE TABLE deployment_control.identity(identity text); INSERT INTO deployment_control.identity VALUES ('fixture')")
            db.execute("INSERT INTO experiences(title,experience_type,listing_status) VALUES ('fixture','CLASS','OPEN')")
            db.execute("ALTER TABLE experiences ADD CONSTRAINT hobbytree_v45_rollback_guard CHECK (listing_status <> 'COMING_SOON')")
            gate.database = dict(dbname=name, identity='fixture')
            def hold():
                gate.connection = psycopg.connect(dsn.replace('/hobbytree_migration_ci', '/' + name))
                for table in ('public.experiences', 'public.flyway_schema_history', 'deployment_control.identity'):
                    gate.connection.execute('SELECT * FROM ' + table + ' LIMIT 0')
            def assert_backend_blocked():
                release = H.Deployment(Mock(), '/unused', 'sha256:'+'2'*64, 'sha256:'+'1'*64, 'a'*40, '123-1')
                release.migration_gate = gate
                release.compose = Mock()
                with self.assertRaises((H.ReleaseError, psycopg.Error)):
                    release.start_backend()
                release.compose.assert_not_called()
            hold()
            gate.check()
            with self.assertRaises(psycopg.errors.CheckViolation):
                db.execute("UPDATE experiences SET listing_status='COMING_SOON'")
            db.execute("SET lock_timeout='250ms'")
            with self.assertRaises(psycopg.errors.LockNotAvailable):
                db.execute('ALTER TABLE experiences DROP CONSTRAINT hobbytree_v45_rollback_guard')
            db.execute("UPDATE flyway_schema_history SET checksum=0 WHERE version='5'")
            with self.assertRaisesRegex(H.ReleaseError, 'Flyway history'):
                gate.check()
            gate.close()
            db.execute("UPDATE flyway_schema_history SET checksum=%s WHERE version='5'", (G.HISTORY[-1][2],))
            for end in ('commit', 'rollback'):
                hold()
                gate.check()
                getattr(gate.connection, end)()
                with self.assertRaisesRegex(H.ReleaseError, 'transaction ended'):
                    gate.check()
                assert_backend_blocked()
                gate.close()
            hold()
            gate.check()
            admin.execute('SELECT pg_terminate_backend(%s)', (gate.connection.info.backend_pid,))
            with self.assertRaises((H.ReleaseError, psycopg.Error)):
                gate.check()
            gate.close()
            gate.connection = psycopg.connect(dsn.replace('/hobbytree_migration_ci', '/' + name))
            gate.connection.execute('SELECT * FROM public.experiences LIMIT 0')
            with self.assertRaisesRegex(H.ReleaseError, 'locks lost'):
                gate.check()
            gate.close()
            db.execute('ALTER TABLE experiences DROP CONSTRAINT hobbytree_v45_rollback_guard')
            db.execute("UPDATE experiences SET listing_status='COMING_SOON'")
            hold()
            with self.assertRaisesRegex(H.ReleaseError, 'COMING_SOON'):
                gate.check()
            assert_backend_blocked()
        finally:
            gate.close()
            if db:
                db.close()
            admin.execute('DROP DATABASE ' + name)
            admin.close()
