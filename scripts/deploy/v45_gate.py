"""Offline-signed, single-use V3 -> V5 gate; never execute migration SQL."""

import json
import os
from pathlib import Path
import re
import subprocess
import time
from urllib.parse import urlsplit, parse_qs

CONFIG = Path('/etc/hobbytree/v45/config.json')
APPROVALS = Path('/etc/hobbytree/v45/approvals')
LEGACY = 'be9375a8dde209c300e592c41f9c4addd8ebc793'
BEFORE = '65c88b864b804ed8ecc76e18c13a4d4d51c509cab0bbbe688a4c05a884f93903'
AFTER = '84bbd4049e3e926c5b725d3c3213b2d6980202973aaba31299f1a4999dee0026'
HISTORY = [
    ('1', 'V1__init_schema.sql', 1798696397, True),
    ('2', 'V2__update_user_auth_policy.sql', -776995949, True),
    ('3', 'V3__enforce_user_withdrawal_backfill.sql', -1230142336, True),
    ('4', 'V4__add_content_reports.sql', -498665831, True),
    ('5', 'V5__add_experience_coming_soon_status.sql', 687484878, True),
]
GUARD = "CHECK (((listing_status)::text <> 'COMING_SOON'::text))"


class Gate:
    """Trust a protected public key, then hold DB DDL locks through release/rollback."""

    def __init__(self, approval_id, host):
        self.host, self.connection = host, None
        self.require = host.require
        self.require(bool(re.fullmatch(r'[0-9a-f]{32}', approval_id)), 'Invalid V45 approval ID.')
        self.approval_id = approval_id

    def read(self, path):
        """Bound protected configuration and approval reads, never emit their contents."""
        self.host.protected(path)
        self.require(path.stat().st_size <= 16384, 'V45 file too large.')
        return path.read_bytes()

    def authorize(self, release, old_schema, new_schema):
        """Bind exact image revisions, digest pair, SQL sets, DB and short lifetime."""
        config = json.loads(self.read(CONFIG))
        # Observe the database actually used by the running backend and reviewed Compose.
        jdbc = release.compose_env.get('DB_URL', '')
        self.require(jdbc.startswith('jdbc:postgresql://'), 'Backend DB URL missing.')
        target = urlsplit(jdbc[5:])
        query = parse_qs(target.query, keep_blank_values=True)
        db = config['database']
        self.require(target.hostname == db['host'] and (target.port or 5432) == db['port']
                     and target.path == '/' + db['dbname'] and not target.username and not target.password
                     and not target.fragment and set(query) <= {'sslmode', 'sslrootcert'}
                     and query.get('sslmode') == ['verify-full'], 'Backend and approval DB targets differ.')
        body = APPROVALS / (self.approval_id + '.json')
        signature = APPROVALS / (self.approval_id + '.sig')
        self.read(signature)
        self.read(Path(config['public_key']))
        raw = self.read(body)
        verified = subprocess.run(
            ['/usr/bin/openssl', 'dgst', '-sha256', '-verify', config['public_key'],
             '-signature', str(signature)],
            input=raw, capture_output=True, timeout=10, env=release.cmd.env)
        self.require(verified.returncode == 0, 'V45 approval signature invalid.')
        proof = json.loads(raw)
        now = int(time.time())
        self.require(type(proof.get('issued_at')) is int and type(proof.get('expires_at')) is int,
                     'V45 approval time missing.')
        self.require(proof['issued_at'] <= now < proof['expires_at'] and
                     0 < proof['expires_at'] - proof['issued_at'] <= 7200 and
                     proof['expires_at'] - now >= self.host.FORWARD_SECONDS,
                     'V45 approval expired or future-dated.')
        expected = dict(format=1, approval_id=self.approval_id, previous=release.previous,
                        candidate=release.image, previous_revision=LEGACY, revision=release.revision,
                        before_schema=BEFORE, after_schema=AFTER, database=config['database'])
        self.require(all(proof.get(k) == v for k, v in expected.items()), 'V45 approval context mismatch.')
        self.require(old_schema == BEFORE and new_schema == AFTER, 'Only reviewed V1-V3 to V1-V5 SQL allowed.')
        self.require(bool(re.fullmatch(r'[0-9a-f]{64}', proof.get('validation_report_sha256', ''))),
                     'Signed validation report hash required.')
        report = self.read(APPROVALS / (self.approval_id + '.report.json'))
        self.require(self.host.sha(report) == proof['validation_report_sha256'], 'Validation report missing or changed.')
        results = json.loads(report)
        self.require(results == dict(revision=release.revision, candidate=release.image,
                                    migration_tests=9, failures=0, errors=0, skipped=0,
                                    docker_validation='success'), 'Validation results incomplete.')
        old = release.image_info(release.old_image_id)
        self.require(release.previous in old.get('RepoDigests', []) and
                     (old['Config'].get('Labels') or {}).get('org.opencontainers.image.revision') == LEGACY,
                     'Rollback image digest/revision unsupported.')
        return config

    def prepare(self, release, old_schema, new_schema):
        """Check live V5 state and persistent write barrier before consuming approval."""
        config = self.authorize(release, old_schema, new_schema)
        import psycopg
        db = config['database']
        self.require(set(db) == {'host', 'port', 'dbname', 'identity'}, 'Explicit DB identity required.')
        self.require(db['port'] == 5432 and re.fullmatch(r'[a-z0-9.-]+\.rds\.amazonaws\.com', db['host']),
                     'Fixed RDS endpoint required.')
        self.read(Path(config['password_file']))
        self.host.protected(Path(config['ca_file']))
        self.require(Path(config['password_file']).stat().st_mode & 0o077 == 0, 'DB password file must be private.')
        self.database = db
        self.connection = psycopg.connect(
            host=db['host'], port=db['port'], dbname=db['dbname'], user=config['reader'],
            password=Path(config['password_file']).read_text().strip(),
            sslmode='verify-full', sslrootcert=config['ca_file'], connect_timeout=5,
            options='-c search_path=pg_catalog -c statement_timeout=5000 -c lock_timeout=3000')
        self.connection.execute('SET TRANSACTION READ ONLY')
        self.require(self.connection.execute(
            'SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname=current_user'
        ).fetchone() == (False,), 'Restricted DB reader required.')
        self.require(self.connection.execute(
            "SELECT pg_has_role(current_user,relowner,'USAGE') FROM pg_class WHERE oid='public.experiences'::regclass"
        ).fetchone() == (False,), 'DB reader must not own experiences.')
        self.require(self.connection.execute(
            "SELECT has_schema_privilege(current_user,'public','CREATE')"
        ).fetchone() == (False,), 'DB reader must not create schema objects.')
        # AccessShare locks prevent ALTER/DROP of the barrier and evidence tables, but permit DML.
        for table in ('public.experiences', 'public.flyway_schema_history', 'deployment_control.identity'):
            self.connection.execute('SELECT * FROM ' + table + ' LIMIT 0')
        self.check()
        self.consume(release)

    def consume(self, release):
        """Durably burn one approval before any backend change; never allow replay."""
        marker = self.host.STATE / ('v45-' + self.approval_id + '.used')
        descriptor = os.open(marker, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, 'wb') as output:
            output.write(release.request_id.encode())
            output.flush()
            os.fsync(output.fileno())
        directory = os.open(self.host.STATE, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
        release.record['v45_approval'] = self.approval_id

    def check(self):
        """Fail closed on lost session, changed history, missing barrier or new enum data."""
        self.require(self.connection is not None and not self.connection.closed, 'V45 DB session lost; manual recovery required.')
        db = self.connection.execute('SELECT current_database()').fetchone()[0]
        identity = self.connection.execute('SELECT identity FROM deployment_control.identity').fetchall()
        history = self.connection.execute(
            'SELECT version,script,checksum,success FROM public.flyway_schema_history ORDER BY installed_rank').fetchall()
        guard = self.connection.execute(
            "SELECT convalidated,pg_get_constraintdef(oid) FROM pg_constraint "
            "WHERE conrelid='public.experiences'::regclass AND conname='hobbytree_v45_rollback_guard'").fetchall()
        rows = self.connection.execute("SELECT 1 FROM public.experiences WHERE listing_status='COMING_SOON' LIMIT 1").fetchall()
        self.require(db == self.database['dbname'] and identity == [(self.database['identity'],)], 'Actual DB identity mismatch.')
        self.require(history == HISTORY, 'Actual Flyway history must be exactly successful V1-V5.')
        self.require(guard == [(True, GUARD)] and not rows, 'Rollback incompatible: COMING_SOON barrier/data invalid.')

    def close(self):
        """Release only this gate's read transaction, never mutate application data."""
        if self.connection is not None:
            self.connection.close()
