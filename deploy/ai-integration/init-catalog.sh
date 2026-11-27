#!/bin/bash
# Only on a NEW dedicated volume; no existing BE database is reachable here.
set -eu
set +x
export ITEM_INIT_PASSWORD
ITEM_INIT_PASSWORD="$(cat /run/secrets/item_db_init_password)"
[[ "$ITEM_INIT_PASSWORD" =~ ^[A-Za-z0-9_-]{32,128}$ ]] || exit 1
psql -v ON_ERROR_STOP=1 --username postgres --dbname postgres <<'SQL'
\getenv item_password ITEM_INIT_PASSWORD
CREATE ROLE item_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'item_password';
CREATE DATABASE hobbytree_items OWNER item_app;
SQL
unset ITEM_INIT_PASSWORD
psql -v ON_ERROR_STOP=1 --username postgres --dbname hobbytree_items <<'SQL'
SET ROLE item_app;
\i /opt/item-sql/001_catalog.sql
SQL
# pg_cron is installed/preloaded, but 002_pg_cron.sql is NOT applied here.
# Scheduling and provider collection require a separate policy/application step.
