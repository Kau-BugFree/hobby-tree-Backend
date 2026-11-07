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
public final class SchemaMigrationCli {
  private SchemaMigrationCli() {}

  static void require(boolean condition) {
    if (!condition) throw new IllegalStateException("Transition verification failed");
  }

  static List<String> rows(Connection c, String sql) throws Exception {
    try (var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      var values = new ArrayList<String>();
      while (r.next()) values.add(r.getString(1));
      return values;
    }
  }

  static List<String> history(Connection c) throws Exception {
    return rows(
        c,
        "SELECT version || E'\t' || script || E'\t' || checksum FROM public.flyway_schema_history WHERE success ORDER BY installed_rank");
  }

  static java.util.Set<String> approvedTables(List<String> expected) throws Exception {
    var tables = new java.util.HashSet<String>();
    var pattern =
        java.util.regex.Pattern.compile(
            "(?im)^\\s*CREATE TABLE(?: IF NOT EXISTS)?\\s+([a-z_][a-z0-9_]*)\\s*\\(");
    for (String row : expected) {
      String script = row.split("\t")[1];
      require(script.matches("V[1-9][0-9]*__[A-Za-z0-9_]+\\.sql"));
      try (var input = SchemaMigrationCli.class.getResourceAsStream("/db/migration/" + script)) {
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

  static void auditDestructiveChanges(Connection c, int baseline, int target) throws Exception {
    if (baseline < 9 && target >= 9) {
      require(rows(c, "SELECT count(*) FROM public.user_locations").equals(List.of("0")));
      require(rows(c, "SELECT count(*) FROM public.location_consent_history").equals(List.of("0")));
    }
    // Conservative: even notification-only rows require a separate reviewed V11 data audit.
    if (baseline < 11 && target >= 11) {
      require(rows(c, "SELECT count(*) FROM public.user_preferences").equals(List.of("0")));
    }
    require(
        rows(c, "SELECT count(*) FROM pg_constraint WHERE conname='hobbytree_v45_rollback_guard'")
            .equals(List.of("0")));
  }

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
  static String writerFence(Connection c, String user, String appUser, boolean appAllowed)
      throws Exception {
    require(!user.equals(appUser));
    requireRestrictedRole(c, user);
    String managedSuperuser =
        c.getMetaData().getURL().startsWith("jdbc:postgresql://127.0.0.1:")
            ? "postgres"
            : "rdsadmin";
    require(
        rows(c, "SELECT rolname FROM pg_roles WHERE rolsuper AND rolcanlogin ORDER BY rolname")
            .equals(List.of(managedSuperuser)));
    require(
        rows(c, "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()")
            .equals(List.of(user)));
    require(rows(c, "SELECT current_user").equals(List.of(user)));
    require(
        rows(
                c,
                "SELECT count(*) FROM pg_roles WHERE rolcanlogin AND NOT rolsuper AND rolname <> '"
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
            "SELECT oid::text || '|' || datdba::text || '|' || COALESCE(datacl::text,'') FROM pg_database WHERE datname=current_database()");
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(
                    String.join("\n", evidence).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

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

  static String verifyFence(Connection c, String user, String appUser, String expectedFence)
      throws Exception {
    String actual = writerFence(c, user, appUser, false);
    if (expectedFence != null) require(actual.equals(expectedFence));
    return actual;
  }

  public static String run(
      String mode,
      Path expectedFile,
      int baseline,
      String url,
      String user,
      String password,
      String appUser)
      throws Exception {
    return run(mode, expectedFile, baseline, url, user, password, appUser, null);
  }

  public static String run(
      String mode,
      Path expectedFile,
      int baseline,
      String url,
      String user,
      String password,
      String appUser,
      String expectedFence)
      throws Exception {
    require(
        List.of(
                "check-before",
                "check-fence",
                "migrate",
                "check-after",
                "validate-app",
                "allow-app",
                "close-app")
            .contains(mode));
    require(appUser != null && appUser.matches("[a-z_][a-z0-9_]{0,62}"));
    require(user != null && user.matches("[a-z_][a-z0-9_]{0,62}") && !user.equals(appUser));
    var expected = Files.readAllLines(expectedFile);
    require(!expected.isEmpty() && baseline > 0 && baseline < expected.size());
    var tables = approvedTables(expected);
    if (List.of("check-fence", "allow-app", "close-app").contains(mode)) {
      try (var c = DriverManager.getConnection(url, user, password);
          var statement = c.createStatement()) {
        statement.execute("SET lock_timeout='5s'; SET statement_timeout='120s'");
        if (mode.equals("close-app"))
          statement.execute(
              "REVOKE CONNECT ON DATABASE " + quote(c.getCatalog()) + " FROM " + quote(appUser));
        String fence =
            verifyFence(c, user, appUser, mode.equals("close-app") ? null : expectedFence);
        if (mode.equals("allow-app")) {
          statement.execute(
              "GRANT CONNECT ON DATABASE " + quote(c.getCatalog()) + " TO " + quote(appUser));
          writerFence(c, user, appUser, true);
        }
        return fence;
      }
    }
    if (mode.equals("validate-app")) {
      try (var c = DriverManager.getConnection(url, user, password)) {
        verifyFence(c, user, appUser, expectedFence);
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
      if (!mode.equals("check-before")) verifyFence(c, user, appUser, expectedFence);
      require(
          rows(c, "SELECT count(*) FROM public.flyway_schema_history WHERE NOT success")
              .equals(List.of("0")));
      if (mode.equals("check-before") || mode.equals("migrate")) {
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
        s.execute("GRANT USAGE ON SCHEMA public TO " + appUser);
        for (String table :
            rows(
                c,
                "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history'")) {
          if (!tables.contains(table)) continue;
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
    } else if (mode.equals("check-before")) {
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

  static String quote(String identifier) {
    require(identifier != null && identifier.matches("[a-z_][a-z0-9_]{0,62}"));
    return "\"" + identifier + "\"";
  }

  public static void main(String[] args) {
    // All diagnostic output is fixed; DB/password/provider errors must never enter SSM logs.
    ((ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
        .setLevel(ch.qos.logback.classic.Level.OFF);
    try {
      require(args.length == 3);
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
      if (List.of("migrate", "check-after", "validate-app", "allow-app").contains(args[0])) {
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
              expectedFence);
      if (fence != null) System.out.println("TRANSITION_FENCE_SHA256=" + fence);
      System.out.println("TRANSITION_PASS=" + args[0]);
    } catch (Throwable error) {
      System.err.println(
          "TRANSITION_FAILED: retain maintenance; inspect protected journal; never auto-start previous image");
      System.exit(1);
    }
  }
}
