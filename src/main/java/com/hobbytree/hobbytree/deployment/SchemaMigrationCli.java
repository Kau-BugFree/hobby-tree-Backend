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

  static void validateJpa(String url, String user, String password) throws Exception {
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", url)
            .applySetting("hibernate.connection.username", user)
            .applySetting("hibernate.connection.password", password)
            .applySetting("hibernate.connection.pool_size", 2)
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
      }
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }

  public static void run(
      String mode,
      Path expectedFile,
      int baseline,
      String url,
      String user,
      String password,
      String appUser)
      throws Exception {
    require(List.of("check-before", "migrate", "check-after", "validate-app").contains(mode));
    require(appUser != null && appUser.matches("[a-z_][a-z0-9_]{0,62}"));
    var expected = Files.readAllLines(expectedFile);
    require(!expected.isEmpty() && baseline > 0 && baseline < expected.size());
    var tables = approvedTables(expected);
    if (mode.equals("validate-app")) {
      require(user.equals(appUser));
      try (var c = DriverManager.getConnection(url, user, password)) {
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
                    "SELECT has_table_privilege(current_user,'public.flyway_schema_history','INSERT')")
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
      }
      validateJpa(url, user, password);

      return;
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
      require(
          rows(c, "SELECT count(*) FROM public.flyway_schema_history WHERE NOT success")
              .equals(List.of("0")));
      if (mode.equals("check-before") || mode.equals("migrate")) {
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
      run(
          args[0],
          Path.of(args[1]),
          Integer.parseInt(args[2]),
          url,
          Files.readString(secrets.resolve("DB_USERNAME")).strip(),
          Files.readString(secrets.resolve("DB_PASSWORD")).strip(),
          System.getenv("TRANSITION_APP_USER"));
      System.out.println("TRANSITION_PASS=" + args[0]);
    } catch (Throwable error) {
      System.err.println(
          "TRANSITION_FAILED: retain maintenance; inspect protected journal; never auto-start previous image");
      System.exit(1);
    }
  }
}
