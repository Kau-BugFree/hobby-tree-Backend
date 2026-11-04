"""Opt-in disposable localhost PG17 regression for the server-enforced rollback barrier."""
import os
import re
import unittest
import uuid
from pathlib import Path
from test_v45_gate import G, H, ROOT

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
            for path in sorted((ROOT / 'src/main/resources/db/migration').glob('V*.sql')):
                db.execute(path.read_text(encoding='utf-8'))
            db.execute('CREATE TABLE flyway_schema_history(installed_rank int, version text, script text, checksum int, success bool)')
            for rank, row in enumerate(G.HISTORY, 1):
                db.execute('INSERT INTO flyway_schema_history VALUES (%s,%s,%s,%s,%s)', (rank, *row))
            db.execute("CREATE SCHEMA deployment_control; CREATE TABLE deployment_control.identity(identity text); INSERT INTO deployment_control.identity VALUES ('fixture')")
            db.execute("INSERT INTO experiences(title,experience_type,listing_status) VALUES ('fixture','CLASS','OPEN')")
            db.execute("ALTER TABLE experiences ADD CONSTRAINT hobbytree_v45_rollback_guard CHECK (listing_status <> 'COMING_SOON')")
            gate.database = dict(dbname=name, identity='fixture')
            gate.connection = psycopg.connect(dsn.replace('/hobbytree_migration_ci', '/' + name))
            gate.connection.execute('SELECT * FROM public.experiences LIMIT 0')
            gate.check()
            with self.assertRaises(psycopg.errors.CheckViolation):
                db.execute("UPDATE experiences SET listing_status='COMING_SOON'")
            db.execute("SET lock_timeout='250ms'")
            with self.assertRaises(psycopg.errors.LockNotAvailable):
                db.execute('ALTER TABLE experiences DROP CONSTRAINT hobbytree_v45_rollback_guard')
            db.execute("UPDATE flyway_schema_history SET checksum=0 WHERE version='5'")
            with self.assertRaises(H.ReleaseError):
                gate.check()
            gate.close()
            db.execute('ALTER TABLE experiences DROP CONSTRAINT hobbytree_v45_rollback_guard')
            db.execute("UPDATE experiences SET listing_status='COMING_SOON'")
            gate.connection = psycopg.connect(dsn.replace('/hobbytree_migration_ci', '/' + name))
            with self.assertRaises(H.ReleaseError):
                gate.check()
        finally:
            gate.close()
            if db:
                db.close()
            admin.execute('DROP DATABASE ' + name)
            admin.close()
