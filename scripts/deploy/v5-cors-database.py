"""Read-only DB gate for the fixed V5-to-V5 CORS security release."""

import json
from pathlib import Path
import re
from urllib.parse import parse_qs, urlsplit


def require_backend_target(jdbc, database, require):
    """Bind the DB reader to the running backend's single verify-full endpoint."""
    require(jdbc.startswith("jdbc:postgresql://"), "Backend DB URL missing.")
    target = urlsplit(jdbc[5:])
    query = parse_qs(target.query, keep_blank_values=True)
    require(target.hostname == database["host"] and (target.port or 5432) == database["port"]
            and target.path == "/" + database["dbname"] and target.username is None
            and target.password is None and not target.fragment
            and query == {"sslmode": ["verify-full"], "sslrootcert": ["/etc/hobbytree/certs/rds-ca.pem"]},
            "Backend and V5 release DB targets differ.")


class Database:
    """Reuse V45's live DB checks and hold the same locks without a V45 approval."""

    def __init__(self, core, v45):
        self.core, self.v45 = core, v45
        self.require = core.require
        self.connection = None

    def prepare(self, release):
        """Read the existing protected reader config and prove the real backend DB target."""
        self.core.protected(self.v45.CONFIG)
        self.require(self.v45.CONFIG.stat().st_size <= 16384, "DB reader config too large.")
        config = json.loads(self.v45.CONFIG.read_bytes())
        database = config["database"]
        self.require(set(database) == {"host", "port", "dbname", "identity"}
                     and database["port"] == 5432
                     and re.fullmatch(r"[a-z0-9.-]+\.rds\.amazonaws\.com", database["host"])
                     and database["identity"], "Explicit RDS identity required.")
        require_backend_target(release.compose_env.get("DB_URL", ""), database, self.require)
        self.open(config)

    def open(self, config):
        """Connect via TLS and retain a read-only transaction across the image change."""
        import psycopg

        self.database = config["database"]
        password_file, ca_file = Path(config["password_file"]), Path(config["ca_file"])
        self.core.protected(password_file)
        self.core.protected(ca_file)
        self.require(password_file.stat().st_mode & 0o077 == 0 and password_file.stat().st_size <= 16384,
                     "DB reader password file must be private and bounded.")
        self.connection = psycopg.connect(
            host=self.database["host"], port=self.database["port"], dbname=self.database["dbname"],
            user=config["reader"], password=password_file.read_text().strip(),
            sslmode="verify-full", sslrootcert=str(ca_file), connect_timeout=5,
            keepalives=1, keepalives_idle=5, keepalives_interval=2, keepalives_count=2,
            tcp_user_timeout=5000,
            options="-c search_path=pg_catalog -c statement_timeout=5000 -c lock_timeout=3000",
        )
        self.connection.execute("SET TRANSACTION READ ONLY")
        for sql, message in (
            ("SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls "
             "FROM pg_roles WHERE rolname=current_user", "Restricted DB reader required."),
            ("SELECT pg_has_role(current_user,relowner,'USAGE') FROM pg_class "
             "WHERE oid='public.experiences'::regclass", "DB reader must not own experiences."),
            ("SELECT has_schema_privilege(current_user,'public','CREATE')",
             "DB reader must not create schema objects."),
        ):
            self.require(self.connection.execute(sql).fetchone() == (False,), message)
        for table in ("public.experiences", "public.flyway_schema_history", "deployment_control.identity"):
            self.connection.execute("SELECT * FROM " + table + " LIMIT 0")
        self.check()

    def check(self):
        """Recheck exact V1–V5 history, DB identity, locks and the validated rollback barrier."""
        self.v45.Gate.check(self)

    def close(self):
        """Release the existing read transaction without writing any application data."""
        self.v45.Gate.close(self)
