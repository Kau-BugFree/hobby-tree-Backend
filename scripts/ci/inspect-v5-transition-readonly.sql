-- READ ONLY. Run with psql -X -v ON_ERROR_STOP=1 -v app_role='<verified app role>'
-- Use an already approved TLS verify-full connection to hobbytree. No passwords in argv/logs.
-- Do not substitute hobbytree_migration for the application role.
BEGIN READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';
SELECT current_database(), current_user, session_user, version();
SELECT datname, pg_get_userbyid(datdba) AS database_owner, datacl
FROM pg_database WHERE datname=current_database();
SELECT d.datname, a.grantor::regrole, a.grantee, a.privilege_type, a.is_grantable
FROM pg_database d, LATERAL aclexplode(COALESCE(d.datacl,acldefault('d',d.datdba))) a
WHERE d.datname=current_database() ORDER BY a.grantee,a.privilege_type,a.grantor;
SELECT rolname, rolcanlogin, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls,
 has_database_privilege(oid,current_database(),'CONNECT') AS effective_connect,
 has_database_privilege(oid,current_database(),'CONNECT WITH GRANT OPTION') AS connect_grant_option
FROM pg_roles ORDER BY rolname;
-- Exact policy inventory: every returned LOGIN must be reviewed and explicitly listed in plan.
SELECT r.rolname FROM pg_roles r WHERE r.rolcanlogin AND
 (r.rolsuper OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication OR r.rolbypassrls OR
 r.oid=(SELECT datdba FROM pg_database WHERE datname=current_database()) OR
 EXISTS (SELECT 1 FROM pg_roles a WHERE a.rolname IN
 ('rds_superuser','rds_replication','pg_write_server_files','pg_read_server_files',
 'pg_execute_server_program','pg_signal_backend','pg_create_subscription','pg_read_all_data','pg_write_all_data')
 AND pg_has_role(r.oid,a.oid,'MEMBER'))) ORDER BY r.rolname;
SELECT roleid::regrole, member::regrole, grantor::regrole, admin_option, inherit_option, set_option
FROM pg_auth_members ORDER BY roleid,member;
SELECT r.rolname, pg_has_role(r.oid,'postgres','MEMBER') AS postgres_member,
 pg_has_role(r.oid,'postgres','SET') AS postgres_set,
 pg_has_role('hobbytree_migration',r.oid,'MEMBER') AS migration_member,
 pg_has_role('hobbytree_migration',r.oid,'SET') AS migration_set
FROM pg_roles r ORDER BY r.rolname;
SELECT nspname, nspowner::regrole, nspacl,
 has_schema_privilege('hobbytree_migration',oid,'USAGE') AS migration_usage,
 has_schema_privilege('hobbytree_migration',oid,'CREATE') AS migration_create,
 has_schema_privilege(:'app_role',oid,'USAGE') AS app_usage,
 has_schema_privilege(:'app_role',oid,'CREATE') AS app_create
FROM pg_namespace WHERE nspname='public';
SELECT c.oid::regclass AS object, c.relkind, c.relowner::regrole, c.relacl,
 c.relrowsecurity, c.relforcerowsecurity
FROM pg_class c WHERE c.relnamespace='public'::regnamespace AND c.relkind IN ('r','p','S','v','m','f')
ORDER BY c.relkind,c.relname;
SELECT seq.oid::regclass AS sequence, seq.relowner::regrole AS owner,
 tab.oid::regclass AS dependent_table, att.attname, dep.deptype,
 has_sequence_privilege('hobbytree_migration',seq.oid,'USAGE') AS migration_usage,
 has_sequence_privilege('hobbytree_migration',seq.oid,'SELECT') AS migration_select,
 has_sequence_privilege(:'app_role',seq.oid,'USAGE') AS app_usage
FROM pg_class seq LEFT JOIN pg_depend dep ON dep.objid=seq.oid AND dep.classid='pg_class'::regclass AND dep.refclassid='pg_class'::regclass AND dep.deptype IN ('a','i')
LEFT JOIN pg_class tab ON tab.oid=dep.refobjid
LEFT JOIN pg_attribute att ON att.attrelid=tab.oid AND att.attnum=dep.refobjsubid
WHERE seq.relkind='S' AND seq.relnamespace='public'::regnamespace ORDER BY seq.relname;
SELECT c.oid::regclass, has_table_privilege(:'app_role',c.oid,'SELECT') AS app_select,
 has_table_privilege(:'app_role',c.oid,'INSERT') AS app_insert,
 has_table_privilege(:'app_role',c.oid,'UPDATE') AS app_update,
 has_table_privilege(:'app_role',c.oid,'DELETE') AS app_delete,
 pg_has_role(:'app_role',c.relowner,'USAGE') AS app_effective_owner
FROM pg_class c WHERE c.relnamespace='public'::regnamespace AND c.relkind IN ('r','p') ORDER BY c.relname;
SELECT pg_has_role('hobbytree_migration',:'app_role','SET') AS migration_set_app,
 has_table_privilege(:'app_role','public.flyway_schema_history','INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER') AS app_history_write,
 has_any_column_privilege(:'app_role','public.flyway_schema_history','INSERT,UPDATE') AS app_history_column_write;
SELECT installed_rank,version,script,checksum,success FROM public.flyway_schema_history ORDER BY installed_rank;
SELECT conrelid::regclass, conname, contype, convalidated, pg_get_constraintdef(oid)
FROM pg_constraint WHERE conname='hobbytree_v45_rollback_guard';
SELECT listing_status,count(*) FROM public.experiences GROUP BY listing_status ORDER BY listing_status;
SELECT 'user_locations' AS object,count(*) FROM public.user_locations
UNION ALL SELECT 'location_consent_history',count(*) FROM public.location_consent_history
UNION ALL SELECT 'user_preferences',count(*) FROM public.user_preferences;
SELECT p.oid::regprocedure, p.proowner::regrole, l.lanname, p.prosecdef, p.proacl
FROM pg_proc p JOIN pg_language l ON p.prolang=l.oid WHERE p.pronamespace='public'::regnamespace ORDER BY 1;
SELECT lanname,lanpltrusted,has_language_privilege('hobbytree_migration',oid,'USAGE') AS migration_usage
FROM pg_language WHERE lanname IN ('sql','plpgsql');
SELECT defaclrole::regrole,defaclnamespace::regnamespace,defaclobjtype,defaclacl FROM pg_default_acl ORDER BY 1,2,3;
SELECT tgrelid::regclass,tgname,tgenabled,tgfoid::regprocedure FROM pg_trigger
WHERE NOT tgisinternal AND tgrelid IN (SELECT oid FROM pg_class WHERE relnamespace='public'::regnamespace) ORDER BY 1,2;
SELECT pid,usename,application_name,client_addr,state,backend_type,xact_start
FROM pg_stat_activity WHERE datname=current_database() ORDER BY pid;
SELECT gid,owner,database,prepared FROM pg_prepared_xacts WHERE database=current_database();
SELECT subname,subenabled FROM pg_subscription WHERE subdbid=(SELECT oid FROM pg_database WHERE datname=current_database());
SELECT extname,extversion FROM pg_extension ORDER BY extname;
COMMIT;
