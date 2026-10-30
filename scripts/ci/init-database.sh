#!/bin/bash
(
set -euo pipefail
# Only the throwaway CI database is initialized. Flyway is never invoked here.
if [ "$POSTGRES_DB" != hobbytree_ci ] || [ "$POSTGRES_USER" != ci_migration ]; then
  echo 'CI database identity mismatch.' >&2
  exit 1
fi
psql --no-psqlrc --quiet --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" --file /ci/V1__init_schema.sql
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
