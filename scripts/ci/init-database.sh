#!/bin/bash
(
set -euo pipefail
# Only the throwaway CI database is initialized. Flyway is never invoked here.
if [ "$POSTGRES_DB" != hobbytree_ci ] || [ "$POSTGRES_USER" != ci_migration ]; then
  echo 'CI database identity mismatch.' >&2
  exit 1
fi
# Apply every migration found on the mounted directory, in version order, except V3: it only
# adds a CHECK constraint over columns V2 already added, and CI does not exercise it yet (see
# the known-issue note in project memory). Adding V7, V8, ... needs no change to this script.
for migration in $(ls /ci/migrations/V*.sql | sort -V); do
  case "$(basename "$migration")" in
    V3__*) continue ;;
  esac
  # Match Flyway's per-migration transaction: V19's audit/lock/backfill must be atomic.
  psql --no-psqlrc --quiet --single-transaction --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" --file "$migration"
done
psql --no-psqlrc --quiet --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
DO $$
BEGIN
  EXECUTE format('CREATE ROLE ci_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L',
                 pg_read_file('/run/ci/app-password'));
END $$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ci_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ci_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ci_app;
SQL
psql --no-psqlrc --quiet --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" \
  --dbname postgres --command 'CREATE DATABASE hobbytree_ci_empty;'
)
