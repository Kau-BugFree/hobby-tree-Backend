package com.hobbytree.hobbytree.deployment;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Uses only the network-isolated PostgreSQL namespace shared with the V24 rehearsal. */
@EnabledIfEnvironmentVariable(named = "V24_TRANSITION_TEST", matches = "true")
class ReleaseMigrationCliIsolatedTests {
  private static final String URL = "jdbc:postgresql://127.0.0.1:5432/hobbytree_release_lab";
  private static final String ADMIN = "jdbc:postgresql://127.0.0.1:5432/postgres";
  private static final String USER = "release_migration";
  private static final String APP = "release_app";
  private static final V24MigrationCli.DatabasePolicy POLICY =
      new V24MigrationCli.DatabasePolicy("postgres", List.of("postgres"));
  @TempDir Path sql;
  List<String> expected;

  @BeforeEach
  void setup() throws Exception {
    try (var c = DriverManager.getConnection(ADMIN, "postgres", "");
        var s = c.createStatement()) {
      s.execute("DROP DATABASE IF EXISTS hobbytree_release_lab");
      s.execute("DROP ROLE IF EXISTS release_migration");
      s.execute("DROP ROLE IF EXISTS release_app");
      s.execute("CREATE ROLE release_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
      s.execute("CREATE ROLE release_migration LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
      s.execute("GRANT release_app TO release_migration WITH INHERIT FALSE, SET TRUE");
      s.execute("CREATE DATABASE hobbytree_release_lab");
      s.execute("REVOKE CONNECT ON DATABASE hobbytree_release_lab FROM PUBLIC");
      s.execute("GRANT CONNECT ON DATABASE hobbytree_release_lab TO release_migration,release_app");
    }
    try (var c = DriverManager.getConnection(URL, "postgres", "");
        var s = c.createStatement()) {
      s.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
      s.execute("GRANT USAGE,CREATE ON SCHEMA public TO release_migration");
      s.execute("GRANT USAGE ON SCHEMA public TO release_app");
      // Execute the actual separately reviewed installer, replacing only psql quoting variables.
      String installer =
          Files.readString(Path.of("deploy/main-release/install-fence.sql"))
              .replace("\\set ON_ERROR_STOP on", "")
              .replace(":\"migration_role\"", "\"release_migration\"")
              .replace(":'migration_role'", "'release_migration'")
              .replace(":'app_role'", "'release_app'");
      int start = installer.indexOf("SELECT format($ddl$");
      int end = installer.indexOf("\\gexec", start);
      s.execute(installer.substring(0, start));
      String definition;
      try (var result = s.executeQuery(installer.substring(start, end))) {
        result.next();
        definition = result.getString(1);
      }
      s.execute(definition);
      s.execute(installer.substring(end + "\\gexec".length()));
    }
    Flyway.configure()
        .dataSource(URL, USER, "")
        .locations("classpath:db/migration")
        .target("24")
        .load()
        .migrate();
    try (var paths = Files.list(Path.of("src/main/resources/db/migration"))) {
      for (Path path : paths.toList()) Files.copy(path, sql.resolve(path.getFileName()));
    }
    Files.writeString(
        sql.resolve("V25__release_fixture.sql"),
        "CREATE TABLE release_probe(id integer PRIMARY KEY); INSERT INTO release_probe VALUES (1);");
    Files.writeString(
        sql.resolve("V26__release_fixture.sql"), "ALTER TABLE release_probe ADD COLUMN note text;");
    expected = historyFromResources();
    try (var c = DriverManager.getConnection(URL, USER, "");
        var s = c.createStatement()) {
      s.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO release_app");
      s.execute("REVOKE ALL ON flyway_schema_history FROM release_app");
      s.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO release_app");
      s.execute(
          "INSERT INTO users(user_id,provider,provider_user_id,account_status) VALUES (1,'KAKAO','release-fixture','ACTIVE')");
    }
  }

  private List<String> historyFromResources() {
    return Arrays.stream(
            Flyway.configure()
                .dataSource(URL, USER, "")
                .locations("filesystem:" + sql)
                .load()
                .info()
                .all())
        .map(i -> i.getVersion() + "\t" + i.getScript() + "\t" + i.getChecksum())
        .toList();
  }

  private void run(String mode) throws Exception {
    ReleaseMigrationCli.runAt(mode, expected, 24, URL, USER, "", APP, POLICY, "filesystem:" + sql);
  }

  @Test
  void v24ToFutureVersionsPreservesDataAndValidatesBeforeReopening() throws Exception {
    run("before");
    run("fence");
    run("migrate");
    run("validate");
    run("unfence");
    try (var c = DriverManager.getConnection(URL, APP, "")) {
      assertThat(V24MigrationCli.rows(c, "SELECT provider_user_id FROM users WHERE user_id=1"))
          .containsExactly("release-fixture");
      assertThat(V24MigrationCli.rows(c, "SELECT id FROM release_probe")).containsExactly("1");
      assertThat(
              V24MigrationCli.rows(
                  c, "SELECT has_table_privilege(current_user,'flyway_schema_history','INSERT')"))
          .containsExactly("f");
    }
  }

  @Test
  void failedGroupedMigrationRollsBackEveryPendingVersion() throws Exception {
    Files.writeString(
        sql.resolve("V27__invalid_fixture.sql"),
        "ALTER TABLE release_probe ADD CONSTRAINT impossible CHECK(id < 0);");
    expected = historyFromResources();
    run("fence");
    assertThatThrownBy(() -> run("migrate")).isInstanceOf(Exception.class);
    try (var c = DriverManager.getConnection(URL, USER, "")) {
      assertThat(V24MigrationCli.history(c)).hasSize(24);
      assertThat(V24MigrationCli.rows(c, "SELECT to_regclass('public.release_probe') IS NULL"))
          .containsExactly("t");
    }
    // Explicit recovery proof succeeds; production never blindly retries a failed migration.
    run("restore-before");
  }

  @Test
  void refusedApplicationWriterAndFenceFunctionPrivilegeEscape() throws Exception {
    try (var app = DriverManager.getConnection(URL, APP, "")) {
      assertThatThrownBy(
              () -> app.createStatement().execute("SELECT release_control.set_app_connect(true)"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(() -> run("fence")).isInstanceOf(IllegalStateException.class);
    }
    run("restore-before");
  }

  @Test
  void historyTamperingFailsBeforeMutation() throws Exception {
    try (var c = DriverManager.getConnection(URL, USER, "")) {
      c.createStatement()
          .execute("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='24'");
    }
    assertThatThrownBy(() -> run("before")).isInstanceOf(Exception.class);
  }

  @Test
  void repeatedMigrationCannotApplyGrowthOrOtherChangesTwice() throws Exception {
    run("fence");
    run("migrate");
    assertThatThrownBy(() -> run("migrate")).isInstanceOf(IllegalStateException.class);
    try (var c = DriverManager.getConnection(URL, USER, "")) {
      assertThat(V24MigrationCli.rows(c, "SELECT count(*) FROM release_probe"))
          .containsExactly("1");
    }
  }

  @Test
  void imageOnlyExchangeValidatesExistingSchemaWithoutMigration() throws Exception {
    expected = expected.subList(0, 24);
    Files.delete(sql.resolve("V25__release_fixture.sql"));
    Files.delete(sql.resolve("V26__release_fixture.sql"));
    run("before");
    run("fence");
    run("validate");
    run("unfence");
    // Candidate health failed after CONNECT was restored; explicit old-image recovery is allowed.
    run("restore-before");
  }
}
