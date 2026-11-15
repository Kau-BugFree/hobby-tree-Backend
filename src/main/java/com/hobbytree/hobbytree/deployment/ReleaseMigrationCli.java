package com.hobbytree.hobbytree.deployment;

import static com.hobbytree.hobbytree.deployment.V24MigrationCli.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;

/** Version-neutral release tool; never invoked by application startup. */
public final class ReleaseMigrationCli {
  private ReleaseMigrationCli() {}

  public static void run(
      String mode,
      List<String> expected,
      int baseline,
      String url,
      String user,
      String password,
      String app,
      DatabasePolicy policy)
      throws Exception {
    runAt(mode, expected, baseline, url, user, password, app, policy, "classpath:db/migration");
  }

  static void runAt(
      String mode,
      List<String> expected,
      int baseline,
      String url,
      String user,
      String password,
      String app,
      DatabasePolicy policy,
      String location)
      throws Exception {
    require(
        List.of("before", "fence", "migrate", "validate", "unfence", "restore-before")
            .contains(mode));
    require(baseline >= 24 && expected.size() >= baseline && expected.size() <= 1000);
    for (int i = 0; i < expected.size(); i++) {
      require(
          expected.get(i).matches((i + 1) + "\\tV" + (i + 1) + "__[A-Za-z0-9_]+\\.sql\\t-?[0-9]+"));
    }
    quote(app);
    var flyway =
        Flyway.configure()
            .dataSource(url, user, password)
            .locations(location)
            .defaultSchema("public")
            .schemas("public")
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .outOfOrder(false)
            .group(true)
            .mixed(false)
            .target(Integer.toString(expected.size()))
            .initSql("SET lock_timeout='5s'; SET statement_timeout='120s'")
            .load();
    require(
        Arrays.stream(flyway.info().all())
            .filter(i -> i.getVersion() != null)
            .map(i -> i.getVersion() + "\t" + i.getScript() + "\t" + i.getChecksum())
            .toList()
            .equals(expected));
    try (var c = DriverManager.getConnection(url, user, password)) {
      requireDatabasePolicy(c, user, policy);
      requireRestrictedRole(c, app);
      require(
          rows(
                  c,
                  "SELECT count(*) FROM public.flyway_schema_history WHERE NOT success OR type <> 'SQL' OR version IS NULL OR checksum IS NULL")
              .equals(List.of("0")));
      boolean before = List.of("before", "fence", "migrate", "restore-before").contains(mode);
      require(history(c).equals(before ? expected.subList(0, baseline) : expected));
      // A one-time, separately reviewed owner installation grants only this fixed CONNECT toggle.
      // The migration role cannot administer arbitrary roles or databases.
      if (mode.equals("fence")) {
        try (var s = c.createStatement()) {
          s.execute("SELECT release_control.set_app_connect(false)");
        }
      }
      if (!mode.equals("before")) {
        boolean alreadyAllowed =
            mode.equals("restore-before")
                && rows(
                        c,
                        "SELECT has_database_privilege('" + app + "',current_database(),'CONNECT')")
                    .equals(List.of("t"));
        writerFence(c, user, app, alreadyAllowed, policy);
      }
      if (mode.equals("validate") || mode.equals("unfence")) {
        try (var s = c.createStatement()) {
          s.execute("SET ROLE " + quote(app));
        }
        c.setReadOnly(true);
        validateJpa(c, app);
        c.setReadOnly(false);
        try (var s = c.createStatement()) {
          s.execute("RESET ROLE");
        }
      }
      if (mode.equals("unfence") || mode.equals("restore-before")) {
        try (var s = c.createStatement()) {
          s.execute("SELECT release_control.set_app_connect(true)");
        }
        writerFence(c, user, app, true, policy);
      }
    }
    if (mode.equals("migrate")) {
      require(flyway.migrate().migrationsExecuted == expected.size() - baseline);
      flyway.validate();
      try (var c = DriverManager.getConnection(url, user, password);
          var s = c.createStatement()) {
        require(history(c).equals(expected));
        // Only migration-owned business objects; never expose Flyway history for writing.
        for (String table :
            rows(
                c,
                "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history' AND tableowner=current_user")) {
          s.execute(
              "GRANT SELECT,INSERT,UPDATE,DELETE ON public." + quote(table) + " TO " + quote(app));
        }
        for (String seq :
            rows(
                c,
                "SELECT sequencename FROM pg_sequences WHERE schemaname='public' AND sequenceowner=current_user")) {
          s.execute("GRANT USAGE,SELECT ON SEQUENCE public." + quote(seq) + " TO " + quote(app));
        }
      }
    }
  }

  public static void main(String[] args) {
    ((ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
        .setLevel(ch.qos.logback.classic.Level.OFF);
    try {
      require(args.length == 4);
      String host = System.getenv("TRANSITION_DB_HOST");
      require(host != null && host.matches("[a-z0-9.-]+\\.rds\\.amazonaws\\.com"));
      String url = System.getenv("DB_URL");
      require(
          ("jdbc:postgresql://"
                  + host
                  + ":5432/hobbytree?sslmode=verify-full&sslrootcert=/etc/hobbytree/certs/rds-ca.pem")
              .equals(url));
      Path secrets = Path.of("/run/secrets/hobbytree");
      String user = Files.readString(secrets.resolve("DB_USERNAME")).strip();
      require(user.equals(System.getenv("TRANSITION_MIGRATION_USER")));
      var policy = Files.readAllLines(Path.of(args[3]));
      require(policy.size() >= 2);
      run(
          args[0],
          Files.readAllLines(Path.of(args[1])),
          Integer.parseInt(args[2]),
          url,
          user,
          Files.readString(secrets.resolve("DB_PASSWORD")).strip(),
          System.getenv("TRANSITION_APP_USER"),
          new DatabasePolicy(policy.getFirst(), policy.subList(1, policy.size())));
      System.out.println("RELEASE_PASS=" + args[0]);
    } catch (Exception failure) {
      System.err.println("RELEASE_DATABASE_FAILED");
      System.exit(1);
    }
  }
}
