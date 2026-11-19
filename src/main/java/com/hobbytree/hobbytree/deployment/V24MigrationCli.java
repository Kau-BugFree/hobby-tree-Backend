package com.hobbytree.hobbytree.deployment;

import jakarta.persistence.Entity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/** Standalone, approval-gated host tool. Never used by normal Spring application startup. */
public final class V24MigrationCli {
  private V24MigrationCli() {}

  /** Fail without exposing connection details or driver messages to host logs. */
  static void require(boolean condition) {
    if (!condition) throw new IllegalStateException("Transition verification failed");
  }

  /** Collect a single-column verification result while closing JDBC resources. */
  static List<String> rows(Connection c, String sql) throws Exception {
    try (var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      var values = new ArrayList<String>();
      while (r.next()) values.add(r.getString(1));
      return values;
    }
  }

  /** Return ordered successful SQL history for comparison with the reviewed package. */
  static List<String> history(Connection c) throws Exception {
    return rows(
        c,
        "SELECT version || E'\t' || script || E'\t' || checksum FROM public.flyway_schema_history WHERE success AND type = 'SQL' ORDER BY installed_rank");
  }

  /** Derive business tables only from the approved migration resources in this JAR. */
  static java.util.Set<String> approvedTables(List<String> expected) throws Exception {
    var tables = new java.util.HashSet<String>();
    var pattern =
        java.util.regex.Pattern.compile(
            "(?im)^\\s*CREATE TABLE(?: IF NOT EXISTS)?\\s+([a-z_][a-z0-9_]*)\\s*\\(");
    for (String row : expected) {
      String script = row.split("\t")[1];
      require(script.matches("V[1-9][0-9]*__[A-Za-z0-9_]+\\.sql"));
      try (var input = V24MigrationCli.class.getResourceAsStream("/db/migration/" + script)) {
        require(input != null);
        var matches =
            pattern.matcher(
                new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        while (matches.find()) tables.add(matches.group(1));
      }
    }
    require(!tables.isEmpty());
    return tables;
  }

  /** Reject reservation data that was not included in this transition rehearsal. */
  static void auditDestructiveChanges(Connection c, int baseline, int target) throws Exception {
    require(baseline == 18 && target == 24);
    // This rollout was reviewed against an empty reservation table. New rows require re-review.
    require(rows(c, "SELECT count(*) FROM public.experience_reservations").equals(List.of("0")));
  }

  /** Validate current entities through the restricted application role without schema writes. */
  static void validateJpa(Connection connection, String appUser) throws Exception {
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting(
                "hibernate.connection.datasource",
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                    connection, true))
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .applySetting("hibernate.default_schema", "public")
            .build();
    try {
      var metadata = new MetadataSources(registry);
      var scanner = new ClassPathScanningCandidateComponentProvider(false);
      scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
      var beans = scanner.findCandidateComponents("com.hobbytree.hobbytree.domain");
      require(!beans.isEmpty());
      for (var bean : beans) metadata.addAnnotatedClass(Class.forName(bean.getBeanClassName()));
      try (var factory = metadata.buildMetadata().buildSessionFactory()) {
        require(factory.isOpen());
        try (var session = factory.openSession()) {
          require(
              session
                  .doReturningWork(
                      c -> {
                        try (var statement = c.createStatement();
                            var result = statement.executeQuery("SELECT current_user")) {
                          result.next();
                          return result.getString(1);
                        }
                      })
                  .equals(appUser));
        }
      }
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }

  /** Durable ordinary-client fence, not an assertion that privileged operators cannot act. */
  static String writerFence(
      Connection c, String user, String appUser, boolean appAllowed, DatabasePolicy policy)
      throws Exception {
    require(!user.equals(appUser));
    requireRestrictedRole(c, user);
    requireDatabasePolicy(c, user, policy);
    require(rows(c, "SELECT current_user").equals(List.of(user)));
    require(
        rows(
                c,
                "SELECT count(*) FROM pg_roles WHERE rolcanlogin AND rolname NOT IN ("
                    + policy.privilegedLogins().stream()
                        .map(r -> "'" + r + "'")
                        .collect(java.util.stream.Collectors.joining(","))
                    + ") AND rolname <> '"
                    + user
                    + "'"
                    + (appAllowed ? " AND rolname <> '" + appUser + "'" : "")
                    + " AND has_database_privilege(oid,current_database(),'CONNECT')")
            .equals(List.of("0")));
    // Explicit PUBLIC ACL check also catches a newly created role that does not exist yet.
    require(
        rows(
                c,
                "SELECT count(*) FROM pg_database d, LATERAL aclexplode(COALESCE(d.datacl,acldefault('d',d.datdba))) a WHERE d.datname=current_database() AND a.grantee=0 AND a.privilege_type='CONNECT'")
            .equals(List.of("0")));
    require(
        rows(c, "SELECT has_database_privilege('" + appUser + "',current_database(),'CONNECT')")
            .equals(List.of(appAllowed ? "t" : "f")));
    require(
        rows(
                c,
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid <> pg_backend_pid() AND (backend_type IS NULL OR backend_type <> 'autovacuum worker')")
            .equals(List.of("0")));
    require(
        rows(c, "SELECT count(*) FROM pg_prepared_xacts WHERE database=current_database()")
            .equals(List.of("0")));
    // Background writers need a separate reviewed policy; do not silently exempt them.
    require(
        rows(
                c,
                "SELECT count(*) FROM pg_subscription WHERE subdbid=(SELECT oid FROM pg_database WHERE datname=current_database()) AND subenabled")
            .equals(List.of("0")));
    require(
        rows(c, "SELECT count(*) FROM pg_extension WHERE extname IN ('pg_cron','pgagent')")
            .equals(List.of("0")));
    var evidence =
        rows(
            c,
            "SELECT d.oid::text || '|' || d.datdba::text || '|' || a.grantor::text || '|' || a.grantee::text || '|' || a.privilege_type || '|' || a.is_grantable::text FROM pg_database d, LATERAL aclexplode(COALESCE(d.datacl,acldefault('d',d.datdba))) a WHERE d.datname=current_database() AND NOT (a.grantee=(SELECT oid FROM pg_roles WHERE rolname='"
                + appUser
                + "') AND a.privilege_type='CONNECT' AND NOT a.is_grantable) ORDER BY 1");
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(
                    String.join("\n", evidence).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  /** Exact, reviewed catalog inventory. No inferred RDS administrator names. */
  public record DatabasePolicy(String owner, List<String> privilegedLogins) {
    public DatabasePolicy {
      quote(owner);
      privilegedLogins = List.copyOf(privilegedLogins);
      require(!privilegedLogins.isEmpty());
      privilegedLogins.forEach(V24MigrationCli::quote);
      require(privilegedLogins.stream().distinct().count() == privilegedLogins.size());
    }
  }

  /** Match the reviewed owner and privileged login inventory; forbid migration privilege escape. */
  static void requireDatabasePolicy(Connection c, String user, DatabasePolicy policy)
      throws Exception {
    require(
        policy != null
            && !user.equals(policy.owner())
            && !policy.privilegedLogins().contains(user));
    requireRestrictedRole(c, user);
    require(
        rows(c, "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()")
            .equals(List.of(policy.owner())));
    require(
        rows(
                c,
                "SELECT pg_has_role(current_user,'"
                    + policy.owner()
                    + "','MEMBER') OR pg_has_role(current_user,'"
                    + policy.owner()
                    + "','SET')")
            .equals(List.of("f")));
    require(
        rows(
                c,
                "SELECT has_database_privilege(current_user,current_database(),'CREATE') OR has_database_privilege(current_user,current_database(),'CONNECT WITH GRANT OPTION')")
            .equals(List.of("f")));
    for (String privileged : policy.privilegedLogins()) {
      require(
          rows(
                  c,
                  "SELECT pg_has_role(current_user,'"
                      + privileged
                      + "','MEMBER') OR pg_has_role(current_user,'"
                      + privileged
                      + "','SET')")
              .equals(List.of("f")));
    }
    var actual =
        rows(
            c,
            "SELECT r.rolname FROM pg_roles r WHERE r.rolcanlogin AND (r.rolsuper OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication OR r.rolbypassrls OR r.oid=(SELECT datdba FROM pg_database WHERE datname=current_database()) OR EXISTS (SELECT 1 FROM pg_roles a WHERE a.rolname IN ('rds_superuser','rds_replication','pg_write_server_files','pg_read_server_files','pg_execute_server_program','pg_signal_backend','pg_create_subscription','pg_read_all_data','pg_write_all_data') AND pg_has_role(r.oid,a.oid,'MEMBER'))) ORDER BY r.rolname");
    require(actual.equals(policy.privilegedLogins().stream().sorted().toList()));
  }

  /** Check that the legacy V4/V5 rollback constraint has exactly the expected state. */
  static void guard(Connection c, boolean present) throws Exception {
    var guards =
        rows(
            c,
            "SELECT conrelid::regclass::text || '|' || contype::text || '|' || convalidated::text || '|' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='hobbytree_v45_rollback_guard'");
    require(
        guards.equals(
            present
                ? List.of(
                    "experiences|c|true|CHECK (((listing_status)::text <> 'COMING_SOON'::text))")
                : List.of()));
  }

  /** Forbid administrator attributes and powerful predefined role memberships. */
  static void requireRestrictedRole(Connection c, String role) throws Exception {
    quote(role);
    require(
        rows(
                c,
                "SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname='"
                    + role
                    + "'")
            .equals(List.of("f")));
    require(
        rows(
                c,
                "SELECT count(*) FROM pg_roles WHERE rolname IN ('rds_superuser','rds_replication','pg_write_server_files','pg_read_server_files','pg_execute_server_program','pg_signal_backend','pg_create_subscription','pg_read_all_data','pg_write_all_data') AND pg_has_role('"
                    + role
                    + "',oid,'MEMBER')")
            .equals(List.of("0")));
  }

  /** Revalidate the ordinary-writer fence and compare its stable ACL fingerprint. */
  static String verifyFence(
      Connection c,
      String user,
      String appUser,
      String expectedFence,
      boolean appAllowed,
      DatabasePolicy policy)
      throws Exception {
    String actual = writerFence(c, user, appUser, appAllowed, policy);
    if (expectedFence != null) require(actual.equals(expectedFence));
    return actual;
  }

  /** Execute one fixed verification or six-migration step; never repair or rewind history. */
  public static String run(
      String mode,
      Path expectedFile,
      int baseline,
      String url,
      String user,
      String password,
      String appUser,
      String expectedFence,
      DatabasePolicy policy)
      throws Exception {
    require(
        List.of(
                "check-before",
                "check-fence",
                "migrate",
                "check-after",
                "validate-app",
                "check-activation",
                "check-active-after",
                "check-ready",
                "validate-active-app")
            .contains(mode));
    require(appUser != null && appUser.matches("[a-z_][a-z0-9_]{0,62}"));
    require(user != null && user.matches("[a-z_][a-z0-9_]{0,62}") && !user.equals(appUser));
    if (!List.of("check-before", "check-fence").contains(mode))
      require(expectedFence != null && expectedFence.matches("[0-9a-f]{64}"));
    var expected = Files.readAllLines(expectedFile);
    require(baseline == 18 && expected.size() == 24);
    for (int i = 0; i < 24; i++) {
      require(
          expected.get(i).matches((i + 1) + "\\tV" + (i + 1) + "__[A-Za-z0-9_]+\\.sql\\t-?[0-9]+"));
    }
    var tables = approvedTables(expected);
    boolean appAllowed =
        List.of("check-activation", "check-active-after", "validate-active-app").contains(mode);
    if (List.of("check-fence", "check-activation").contains(mode)) {
      try (var c = DriverManager.getConnection(url, user, password)) {
        c.setReadOnly(true);
        return verifyFence(c, user, appUser, expectedFence, appAllowed, policy);
      }
    }
    if (mode.equals("validate-app") || mode.equals("validate-active-app")) {
      try (var c = DriverManager.getConnection(url, user, password)) {
        verifyFence(c, user, appUser, expectedFence, appAllowed, policy);
        requireRestrictedRole(c, appUser);
        try (var statement = c.createStatement()) {
          statement.execute("SET ROLE " + quote(appUser));
        }
        c.setReadOnly(true);
        require(
            rows(
                    c,
                    "SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname=current_user")
                .equals(List.of("f")));
        require(
            rows(c, "SELECT has_schema_privilege(current_user,'public','CREATE')")
                .equals(List.of("f")));
        require(
            rows(
                    c,
                    "SELECT has_table_privilege(current_user,'public.flyway_schema_history','INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER') OR has_any_column_privilege(current_user,'public.flyway_schema_history','INSERT,UPDATE') OR pg_has_role(current_user,(SELECT relowner FROM pg_class WHERE oid='public.flyway_schema_history'::regclass),'USAGE')")
                .equals(List.of("f")));
        for (String table :
            rows(
                c,
                "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history'")) {
          if (!tables.contains(table)) continue;
          require(table.matches("[a-z_][a-z0-9_]*"));
          require(
              rows(
                      c,
                      "SELECT has_table_privilege(current_user,'public."
                          + table
                          + "','SELECT') AND has_table_privilege(current_user,'public."
                          + table
                          + "','INSERT') AND has_table_privilege(current_user,'public."
                          + table
                          + "','UPDATE') AND has_table_privilege(current_user,'public."
                          + table
                          + "','DELETE')")
                  .equals(List.of("t")));
          require(
              rows(
                      c,
                      "SELECT pg_has_role(current_user,tableowner,'USAGE') FROM pg_tables WHERE schemaname='public' AND tablename='"
                          + table
                          + "'")
                  .equals(List.of("f")));
        }
        validateJpa(c, appUser);
      }
      return null;
    }
    var flyway =
        Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .defaultSchema("public")
            .schemas("public")
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .outOfOrder(false)
            .target("24")
            .initSql("SET lock_timeout='5s'; SET statement_timeout='120s'")
            .load();
    // Compare every packaged script/CRC with the rehearsal before connecting for mutation.
    var resolved =
        java.util.Arrays.stream(flyway.info().all())
            .filter(i -> i.getVersion() != null)
            .map(i -> i.getVersion() + "\t" + i.getScript() + "\t" + i.getChecksum())
            .toList();
    require(resolved.equals(expected));
    try (var c = DriverManager.getConnection(url, user, password)) {
      c.setReadOnly(true);
      requireDatabasePolicy(c, user, policy);
      if (!mode.equals("check-before"))
        verifyFence(c, user, appUser, expectedFence, appAllowed, policy);
      require(
          rows(
                  c,
                  "SELECT count(*) FROM public.flyway_schema_history WHERE NOT success OR type <> 'SQL' OR version IS NULL OR checksum IS NULL")
              .equals(List.of("0")));
      if (List.of("check-before", "check-ready", "migrate").contains(mode)) {
        guard(c, false);
        require(
            rows(
                    c,
                    "SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind IN ('r','p') AND (relrowsecurity OR relforcerowsecurity)")
                .equals(List.of("0")));
        require(
            rows(
                    c,
                    "SELECT count(*) FROM pg_language WHERE lanname IN ('sql','plpgsql') AND has_language_privilege(current_user,oid,'USAGE')")
                .equals(List.of("2")));

        require(
            rows(
                    c,
                    "SELECT has_schema_privilege(current_user,'public','USAGE') AND has_schema_privilege(current_user,'public','CREATE')")
                .equals(List.of("t")));
        require(
            rows(
                    c,
                    "SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind IN ('r','p','S') AND relowner<>(SELECT oid FROM pg_roles WHERE rolname=current_user)")
                .equals(List.of("0")));
        requireRestrictedRole(c, appUser);
        require(
            rows(c, "SELECT pg_has_role(current_user,'" + appUser + "','SET')")
                .equals(List.of("t")));
        require(
            rows(
                    c,
                    "SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname='"
                        + appUser
                        + "'")
                .equals(List.of("f")));
        require(
            rows(c, "SELECT has_schema_privilege('" + appUser + "','public','CREATE')")
                .equals(List.of("f")));
        require(history(c).equals(expected.subList(0, baseline)));
        auditDestructiveChanges(c, baseline, expected.size());
        require(
            rows(
                    c,
                    "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND NOT pg_has_role(current_user, tableowner, 'USAGE')")
                .equals(List.of("0")));
      } else if (!mode.equals("validate-app")) {
        guard(c, false);
        require(history(c).equals(expected));
      }
    }
    if (mode.equals("migrate")) {
      require(flyway.migrate().migrationsExecuted == expected.size() - baseline);
      flyway.validate();
      try (var c = DriverManager.getConnection(url, user, password);
          var s = c.createStatement()) {
        require(history(c).equals(expected));
        // Grant business objects only; never make the application a history writer or owner.
        require(
            rows(c, "SELECT has_schema_privilege('" + appUser + "','public','USAGE')")
                .equals(List.of("t")));
        for (String table :
            rows(
                c,
                "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history'")) {
          if (!List.of("coach_conversations", "coach_messages").contains(table)) continue;
          require(table.matches("[a-z_][a-z0-9_]*"));
          s.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON public." + table + " TO " + appUser);
          for (String sequence :
              rows(
                  c,
                  "SELECT DISTINCT seq.relname FROM pg_class seq JOIN pg_depend d ON d.objid=seq.oid JOIN pg_class tab ON d.refobjid=tab.oid WHERE seq.relkind='S' AND seq.relnamespace='public'::regnamespace AND tab.relnamespace='public'::regnamespace AND tab.relname='"
                      + table
                      + "'")) {
            require(sequence.matches("[a-z_][a-z0-9_]*"));
            s.execute("GRANT USAGE,SELECT ON SEQUENCE public." + sequence + " TO " + appUser);
          }
        }
      }
    } else if (List.of("check-before", "check-ready").contains(mode)) {
      Flyway.configure()
          .configuration(flyway.getConfiguration())
          .target(Integer.toString(baseline))
          .load()
          .validate();
    } else {
      flyway.validate();
    }
    return null;
  }

  /** Accept only reviewed simple role identifiers before quoting them for PostgreSQL. */
  static String quote(String identifier) {
    require(identifier != null && identifier.matches("[a-z_][a-z0-9_]{0,62}"));
    return "\"" + identifier + "\"";
  }

  /** Separately approved catalogue transaction; never invoked by migration or app startup. */
  static void catalogue(
      String url,
      String user,
      String password,
      Path expectedFile,
      Path sqlFile,
      String sqlHash,
      String request,
      DatabasePolicy policy)
      throws Exception {
    require(sqlHash != null && sqlHash.matches("[0-9a-f]{64}"));
    require(request != null && request.matches("[0-9a-f]{32}"));
    require(Files.size(sqlFile) > 0 && Files.size(sqlFile) <= 2_000_000);
    byte[] raw = Files.readAllBytes(sqlFile);
    require(
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw))
            .equals(sqlHash));
    var expected = Files.readAllLines(expectedFile);
    require(expected.size() == 24);
    try (var c = DriverManager.getConnection(url, user, password)) {
      requireDatabasePolicy(c, user, policy);
      c.setAutoCommit(false);
      try (var statement = c.createStatement()) {
        statement.execute("SET LOCAL lock_timeout='3s'; SET LOCAL statement_timeout='60s'");
        require(history(c).equals(expected));
        require(rows(c, "SELECT count(*) FROM public.flyway_schema_history").equals(List.of("24")));
        try (var settings =
            c.prepareStatement(
                "SELECT set_config('hobbytree.expected_database',current_database(),true),"
                    + "set_config('hobbytree.approval_reference',?,true)")) {
          settings.setString(1, request);
          settings.execute();
        }
        statement.execute(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
        require(history(c).equals(expected));
        require(
            rows(
                    c,
                    "SELECT (SELECT count(*) FROM hobbies)||'|'||"
                        + "(SELECT count(*) FROM hobby_stages)||'|'||(SELECT count(*) FROM activities)||'|'||"
                        + "(SELECT count(*) FROM activity_steps)||'|'||(SELECT count(*) FROM activity_materials)")
                .equals(List.of("3|18|386|1313|1850")));
        c.commit();
      } catch (Exception failure) {
        c.rollback();
        throw failure;
      }
    }
  }

  /** Read protected file credentials and expose only fixed, nonsecret verification outcomes. */
  public static void main(String[] args) {
    // All diagnostic output is fixed; DB/password/provider errors must never enter SSM logs.
    ((ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
        .setLevel(ch.qos.logback.classic.Level.OFF);
    try {
      require(args.length == 4);
      var policyLines = Files.readAllLines(Path.of(args[3]));
      require(policyLines.size() >= 2);
      var policy =
          new DatabasePolicy(policyLines.getFirst(), policyLines.subList(1, policyLines.size()));
      String url = System.getenv("DB_URL");
      String expectedHost = System.getenv("TRANSITION_DB_HOST");
      require(expectedHost != null && expectedHost.matches("[a-z0-9.-]+\\.rds\\.amazonaws\\.com"));
      require(
          url != null
              && url.equals(
                  "jdbc:postgresql://"
                      + expectedHost
                      + ":5432/hobbytree?sslmode=verify-full&sslrootcert=/etc/hobbytree/certs/rds-ca.pem"));
      Path secrets = Path.of("/run/secrets/hobbytree");
      String user = Files.readString(secrets.resolve("DB_USERNAME")).strip();
      require(user.equals(System.getenv("TRANSITION_MIGRATION_USER")));
      String expectedFence = System.getenv("TRANSITION_FENCE_SHA256");
      if (args[0].equals("catalogue")) {
        catalogue(
            url,
            user,
            Files.readString(secrets.resolve("DB_PASSWORD")).strip(),
            Path.of(args[1]),
            Path.of("/run/catalogue.sql"),
            System.getenv("TRANSITION_CATALOGUE_SHA256"),
            System.getenv("TRANSITION_REQUEST"),
            policy);
        System.out.println("TRANSITION_PASS=catalogue");
        return;
      }
      if (!List.of("check-before", "check-fence").contains(args[0])) {
        require(expectedFence != null && expectedFence.matches("[0-9a-f]{64}"));
      }
      String fence =
          run(
              args[0],
              Path.of(args[1]),
              Integer.parseInt(args[2]),
              url,
              user,
              Files.readString(secrets.resolve("DB_PASSWORD")).strip(),
              System.getenv("TRANSITION_APP_USER"),
              expectedFence,
              policy);
      if (fence != null) System.out.println("TRANSITION_FENCE_SHA256=" + fence);
      System.out.println("TRANSITION_PASS=" + args[0]);
    } catch (Throwable error) {
      System.err.println(
          "TRANSITION_FAILED: retain maintenance; inspect protected journal; never auto-start previous image");
      System.exit(1);
    }
  }
}
