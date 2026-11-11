package com.hobbytree.hobbytree.deployment;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Runs only in the separate no-network PostgreSQL namespace created by the CI harness. */
@EnabledIfEnvironmentVariable(named = "V24_TRANSITION_TEST", matches = "true")
class V24MigrationCliIsolatedTests {
  private static final String URL = "jdbc:postgresql://127.0.0.1:5432/hobbytree_v24_lab";
  private static final String ADMIN = "jdbc:postgresql://127.0.0.1:5432/postgres";
  private static final String MIGRATION = "v24_migration";
  private static final String APP = "v24_app";
  private static final V24MigrationCli.DatabasePolicy POLICY =
      new V24MigrationCli.DatabasePolicy("postgres", List.of("postgres"));
  @TempDir Path temporary;
  Path history;
  List<String> expected;

  @BeforeEach
  void isolatedDatabase() throws Exception {
    try (var c = DriverManager.getConnection(ADMIN, "postgres", "")) {
      var s = c.createStatement();
      s.execute("DROP DATABASE IF EXISTS hobbytree_v24_lab");
      s.execute("DROP ROLE IF EXISTS v24_migration");
      s.execute("DROP ROLE IF EXISTS v24_app");
      s.execute("CREATE ROLE v24_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
      s.execute("CREATE ROLE v24_migration LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
      s.execute("GRANT v24_app TO v24_migration WITH INHERIT FALSE, SET TRUE");
      s.execute("CREATE DATABASE hobbytree_v24_lab");
      s.execute("REVOKE CONNECT ON DATABASE hobbytree_v24_lab FROM PUBLIC");
      s.execute("GRANT CONNECT ON DATABASE hobbytree_v24_lab TO v24_migration");
    }
    try (var c = DriverManager.getConnection(URL, "postgres", "")) {
      c.createStatement().execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
      c.createStatement().execute("GRANT USAGE,CREATE ON SCHEMA public TO v24_migration");
      c.createStatement().execute("GRANT USAGE ON SCHEMA public TO v24_app");
    }
    var flyway =
        Flyway.configure()
            .dataSource(URL, MIGRATION, "")
            .locations("classpath:db/migration")
            .cleanDisabled(true)
            .target("18")
            .load();
    flyway.migrate();
    expected =
        Arrays.stream(
                Flyway.configure()
                    .configuration(flyway.getConfiguration())
                    .target("24")
                    .load()
                    .info()
                    .all())
            .map(i -> i.getVersion() + "\t" + i.getScript() + "\t" + i.getChecksum())
            .toList();
    history = temporary.resolve("history.tsv");
    Files.write(history, expected);
    try (var c = migration()) {
      var s = c.createStatement();
      s.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO v24_app");
      s.execute("REVOKE ALL ON public.flyway_schema_history FROM v24_app");
      s.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO v24_app");
      s.execute(
          "INSERT INTO users(user_id,provider,provider_user_id,account_status) VALUES (1,'KAKAO','synthetic-v24','ACTIVE')");
      s.execute(
          "INSERT INTO user_preferences(user_id,budget_level,available_time_minutes,preferences_version) VALUES (1,'LOW',30,1)");
    }
  }

  private Connection migration() throws Exception {
    return DriverManager.getConnection(URL, MIGRATION, "");
  }

  private String run(String mode, String fence) throws Exception {
    return V24MigrationCli.run(mode, history, 18, URL, MIGRATION, "", APP, fence, POLICY);
  }

  @Test
  void appliesSixMigrationsPreservesPreferencesAndValidatesApplication() throws Exception {
    run("check-before", null);
    String fence = run("check-fence", null);
    run("migrate", fence);
    run("check-after", fence);
    run("validate-app", fence);
    try (var c = migration()) {
      assertThat(V24MigrationCli.history(c)).isEqualTo(expected);
      assertThat(
              V24MigrationCli.rows(
                  c,
                  "SELECT budget_level||'|'||available_time_minutes||'|'||preferences_version FROM user_preferences WHERE user_id=1"))
          .containsExactly("LOW|30|1");
      assertThat(
              V24MigrationCli.rows(
                  c, "SELECT has_table_privilege('v24_app','public.coach_messages','INSERT')"))
          .containsExactly("t");
      assertThat(
              V24MigrationCli.rows(
                  c,
                  "SELECT has_table_privilege('v24_app','public.flyway_schema_history','INSERT')"))
          .containsExactly("f");
    }
    assertThatThrownBy(() -> run("migrate", fence)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void refusesChangedHistoryBeforeAnyMigration() throws Exception {
    try (var c = migration()) {
      c.createStatement()
          .execute("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='18'");
    }
    assertThatThrownBy(() -> run("check-before", null)).isInstanceOf(IllegalStateException.class);
    try (var c = migration()) {
      assertThat(V24MigrationCli.rows(c, "SELECT count(*) FROM flyway_schema_history"))
          .containsExactly("18");
    }
  }

  @Test
  void refusesUnexpectedEffectiveConnectAndLiveApplicationSession() throws Exception {
    try (var c = DriverManager.getConnection(URL, "postgres", "")) {
      c.createStatement().execute("GRANT CONNECT ON DATABASE hobbytree_v24_lab TO v24_app");
    }
    assertThatThrownBy(() -> run("check-fence", null)).isInstanceOf(IllegalStateException.class);
    try (var c = DriverManager.getConnection(URL, "postgres", "")) {
      c.createStatement().execute("REVOKE CONNECT ON DATABASE hobbytree_v24_lab FROM v24_app");
    }
    String fence = run("check-fence", null);
    run("migrate", fence);
    try (var c = DriverManager.getConnection(URL, "postgres", "")) {
      c.createStatement().execute("GRANT CONNECT ON DATABASE hobbytree_v24_lab TO v24_app");
    }
    assertThat(run("check-activation", fence)).isEqualTo(fence);
    try (var c = DriverManager.getConnection(URL, APP, "")) {
      assertThatThrownBy(() -> run("check-activation", fence))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void rejectsTamperedExpectedChecksums() throws Exception {
    Files.writeString(history, Files.readString(history).replace("V24__", "V24__tampered_"));
    assertThatThrownBy(() -> run("check-before", null)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void catalogueHashMismatchNeverExecutesSql() throws Exception {
    Path sql = temporary.resolve("catalogue.sql");
    Files.writeString(sql, "DELETE FROM users;");
    assertThatThrownBy(
            () ->
                V24MigrationCli.catalogue(
                    URL, MIGRATION, "", history, sql, "0".repeat(64), "a".repeat(32), POLICY))
        .isInstanceOf(IllegalStateException.class);
    try (var c = migration()) {
      assertThat(V24MigrationCli.rows(c, "SELECT count(*) FROM users")).containsExactly("1");
    }
  }

  @Test
  void catalogueFailureRollsBackAllChanges() throws Exception {
    String fence = run("check-fence", null);
    run("migrate", fence);
    Path sql = temporary.resolve("catalogue.sql");
    byte[] raw =
        "INSERT INTO hobbies(hobby_code,hobby_name) VALUES ('synthetic','synthetic');"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(sql, raw);
    String hash =
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));
    assertThatThrownBy(
            () ->
                V24MigrationCli.catalogue(
                    URL, MIGRATION, "", history, sql, hash, "a".repeat(32), POLICY))
        .isInstanceOf(IllegalStateException.class);
    try (var c = migration()) {
      assertThat(V24MigrationCli.rows(c, "SELECT count(*) FROM hobbies")).containsExactly("0");
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "V24_CATALOGUE_SQL", matches = ".+")
  void reviewedCatalogueIsIdempotentAndRefusesExistingContentDrift() throws Exception {
    String fence = run("check-fence", null);
    run("migrate", fence);
    Path sql = Path.of(System.getenv("V24_CATALOGUE_SQL"));
    String hash =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sql)));
    for (int attempt = 0; attempt < 2; attempt++) {
      V24MigrationCli.catalogue(URL, MIGRATION, "", history, sql, hash, "a".repeat(32), POLICY);
    }
    try (var c = migration()) {
      assertThat(V24MigrationCli.rows(c, "SELECT count(*) FROM user_preferences"))
          .containsExactly("1");
      assertThat(V24MigrationCli.rows(c, "SELECT count(*) FROM activities")).containsExactly("386");
      c.createStatement()
          .execute("UPDATE hobbies SET hobby_name='synthetic-drift' WHERE hobby_code='reading'");
    }
    assertThatThrownBy(
            () ->
                V24MigrationCli.catalogue(
                    URL, MIGRATION, "", history, sql, hash, "b".repeat(32), POLICY))
        .isInstanceOf(java.sql.SQLException.class);
    try (var c = migration()) {
      assertThat(
              V24MigrationCli.rows(c, "SELECT hobby_name FROM hobbies WHERE hobby_code='reading'"))
          .containsExactly("synthetic-drift");
      assertThat(V24MigrationCli.rows(c, "SELECT count(*) FROM activity_materials"))
          .containsExactly("1850");
    }
  }
}
