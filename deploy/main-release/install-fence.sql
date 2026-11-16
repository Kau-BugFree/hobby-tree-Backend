-- ONE-TIME OWNER INSTALLATION ONLY, after approval; never run by Flyway or ordinary CI.
-- psql --set=app_role=... --set=migration_role=... --file=install-fence.sql
-- Run on the reviewed database as its owner. Identifiers/values use psql SQL quoting.
\set ON_ERROR_STOP on
BEGIN;
CREATE SCHEMA release_control;
REVOKE ALL ON SCHEMA release_control FROM PUBLIC;
GRANT USAGE ON SCHEMA release_control TO :"migration_role";
SELECT format($ddl$
CREATE FUNCTION release_control.set_app_connect(allowed boolean) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $body$
BEGIN
  IF session_user <> %L OR allowed IS NULL THEN
    RAISE EXCEPTION 'release operator required';
  END IF;
  IF allowed THEN
    EXECUTE format('GRANT CONNECT ON DATABASE %%I TO %%I', current_database(), %L);
  ELSE
    EXECUTE format('REVOKE CONNECT ON DATABASE %%I FROM %%I', current_database(), %L);
  END IF;
END;
$body$;
$ddl$, :'migration_role', :'app_role', :'app_role') \gexec
REVOKE ALL ON FUNCTION release_control.set_app_connect(boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION release_control.set_app_connect(boolean) TO :"migration_role";
COMMIT;
