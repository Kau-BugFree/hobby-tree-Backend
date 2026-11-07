package com.hobbytree.hobbytree.migration;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hobbytree.hobbytree.domain.experience.entity.ListingStatus;
import jakarta.persistence.Entity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/** Opt-in migrations against a disposable localhost PostgreSQL, never the application's DB_URL. */
@EnabledIfEnvironmentVariable(named = "MIGRATION_INTEGRATION_TEST", matches = "true")
class FlywayMigrationTests {
  private static final List<String> SCRIPTS =
      List.of(
          "V1__init_schema.sql",
          "V2__update_user_auth_policy.sql",
          "V3__enforce_user_withdrawal_backfill.sql",
          "V4__add_content_reports.sql",
          "V5__add_experience_coming_soon_status.sql");

  private static String adminUrl;
  private static String adminUser;
  private static String adminPassword;
  private String database;
  private String databaseUrl;
  private String migrationUser;
  private String appUser;
  private String fixturePassword;
  private boolean migrationRoleCreated;
  private boolean appRoleCreated;
  private boolean databaseCreated;

  @BeforeAll
  static void requireDedicatedLocalPostgres() throws Exception {
    adminUrl = System.getenv("MIGRATION_TEST_JDBC_URL");
    adminUser = System.getenv("MIGRATION_TEST_USERNAME");
    adminPassword = System.getenv("MIGRATION_TEST_PASSWORD");
    assertThat(adminUrl)
        .matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/hobbytree_migration_ci");
    assertThat(adminUser).isNotBlank();
    assertThat(adminPassword).isNotBlank();
    try (var connection = adminConnection()) {
      assertThat(values(connection, "SELECT current_database()"))
          .containsExactly("hobbytree_migration_ci");
      int version = Integer.parseInt(values(connection, "SHOW server_version_num").getFirst());
      assertThat(version).isBetween(170000, 179999);
      System.out.println("MIGRATION_POSTGRES_VERSION=" + version);
    }
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(adminUrl, adminUser, adminPassword);
  }

  @BeforeEach
  void createOwnedDisposableDatabase() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    database = "migration_case_" + suffix;
    migrationUser = "migration_owner_" + suffix;
    appUser = "migration_app_" + suffix;
    fixturePassword = UUID.randomUUID().toString().replace("-", "");
    databaseUrl = adminUrl.replace("/hobbytree_migration_ci", "/" + database);
    try (var connection = adminConnection()) {
      execute(
          connection, "CREATE ROLE " + migrationUser + " LOGIN PASSWORD '" + fixturePassword + "'");
      migrationRoleCreated = true;
      execute(connection, "CREATE ROLE " + appUser + " LOGIN PASSWORD '" + fixturePassword + "'");
      appRoleCreated = true;
      execute(connection, "CREATE DATABASE " + database + " OWNER " + migrationUser);
      databaseCreated = true;
    }
  }

  @AfterEach
  void removeOnlyThisTestDatabaseAndRoles() throws Exception {
    try (var connection = adminConnection()) {
      if (databaseCreated) execute(connection, "DROP DATABASE " + database);
      if (appRoleCreated) execute(connection, "DROP ROLE " + appUser);
      if (migrationRoleCreated) execute(connection, "DROP ROLE " + migrationUser);
    }
  }

  private Connection migrationConnection() throws SQLException {
    return DriverManager.getConnection(databaseUrl, migrationUser, fixturePassword);
  }

  private Connection appConnection() throws SQLException {
    return DriverManager.getConnection(databaseUrl, appUser, fixturePassword);
  }

  private Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(databaseUrl, migrationUser, fixturePassword)
        .locations("classpath:db/migration")
        .defaultSchema("public")
        .schemas("public")
        .cleanDisabled(true)
        .baselineOnMigrate(false)
        .target(target)
        .load();
  }

  /** Require every committed predecessor and our V18 in exact application order. */
  private List<String> expectedContentVersions() {
    return java.util.stream.IntStream.rangeClosed(1, 18).mapToObj(Integer::toString).toList();
  }

  @Test
  void contentV18FreshInstallAndRestrictedRoleWritesKeepGeneratedMetadataConsistent()
      throws Exception {
    var expectedVersions = expectedContentVersions();
    var migration = flyway("18");
    assertThat(migration.migrate().migrationsExecuted).isEqualTo(expectedVersions.size());
    migration.validate();
    try (var c = migrationConnection()) {
      assertThat(
              values(
                  c,
                  "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank"))
          .containsExactlyElementsOf(expectedVersions);
      execute(
          c,
          "INSERT INTO hobbies(hobby_id,hobby_code,hobby_name) VALUES (9001,'content-new','콘텐츠')");
      execute(
          c,
          "INSERT INTO hobby_stages(hobby_stage_id,hobby_id,stage_number,stage_name) VALUES (9001,9001,0,'단계')");
      execute(c, "GRANT USAGE ON SCHEMA public TO " + appUser);
      execute(c, "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO " + appUser);
      execute(c, "GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO " + appUser);
    }
    try (var c = appConnection()) {
      c.setAutoCommit(false);
      execute(
          c,
          "INSERT INTO activities(activity_id,hobby_stage_id,activity_code,activity_name) VALUES (9001,9001,'content-new','제목')");
      execute(
          c,
          "INSERT INTO activity_steps(activity_id,step_number,instruction,title,is_required) VALUES (9001,1,'본문','제목',true)");
      execute(c, "INSERT INTO activity_materials(activity_id,material_name) VALUES (9001,'준비물')");
      assertThat(
              values(
                  c,
                  "SELECT content_version || '|' || (content_updated_at IS NOT NULL)::text FROM activities WHERE activity_id=9001"))
          .containsExactly("1|true");
      c.commit();
      execute(c, "UPDATE activity_steps SET instruction='다른 본문' WHERE activity_id=9001");
      execute(c, "UPDATE activity_materials SET material_name='다른 준비물' WHERE activity_id=9001");
      assertThat(values(c, "SELECT content_version::text FROM activities WHERE activity_id=9001"))
          .containsExactly("2");
      c.commit();
      assertThat(values(c, "SELECT content_version::text FROM activities WHERE activity_id=9001"))
          .containsExactly("2");
    }
  }

  @Test
  void contentV15UpgradePreservesLegacyDataAndDoesNotGuessUnknownSemanticsOrTimes()
      throws Exception {
    flyway("15").migrate();
    List<String> activitiesBefore,
        stepsBefore,
        materialsBefore,
        recordsBefore,
        savedBefore,
        treesBefore;
    try (var c = migrationConnection()) {
      execute(
          c,
          """
          INSERT INTO users(user_id,provider,provider_user_id,account_status) VALUES(9001,'KAKAO','content-upgrade','ACTIVE');
          INSERT INTO hobbies(hobby_id,hobby_code,hobby_name) VALUES(9001,'content-upgrade','실제 취미');
          INSERT INTO hobby_stages(hobby_stage_id,hobby_id,stage_number,stage_name) VALUES(9001,9001,0,'실제 단계');
          INSERT INTO activities(activity_id,hobby_stage_id,activity_code,activity_name,safety_notes,created_at,updated_at)
            VALUES(9001,9001,'content-upgrade','원본','주의사항','2020-01-01Z','2020-02-01Z');
          INSERT INTO activity_steps(activity_id,step_number,instruction) VALUES(9001,1,'제목과 필수 여부를 알 수 없는 본문');
          INSERT INTO activity_materials(activity_id,material_name) VALUES(9001,'획득 방식 미정');
          INSERT INTO user_hobbies(user_hobby_id,user_id,hobby_id,current_stage_number) VALUES(9001,9001,9001,3);
          INSERT INTO activity_records(user_hobby_id,activity_id,activity_status,growth_counted,growth_stage_number,step_progress)
            VALUES(9001,9001,'COMPLETED',true,2,'{"legacy":"untouched"}');
          INSERT INTO saved_activities(user_id,activity_id) VALUES(9001,9001);
          """);
      activitiesBefore =
          values(c, "SELECT to_jsonb(a)::text FROM activities a ORDER BY activity_id");
      stepsBefore =
          values(c, "SELECT to_jsonb(s)::text FROM activity_steps s ORDER BY activity_step_id");
      materialsBefore =
          values(
              c,
              "SELECT to_jsonb(m)::text FROM activity_materials m ORDER BY activity_material_id");
      recordsBefore =
          values(c, "SELECT to_jsonb(r)::text FROM activity_records r ORDER BY activity_record_id");
      savedBefore =
          values(
              c, "SELECT to_jsonb(s)::text FROM saved_activities s ORDER BY user_id,activity_id");
      treesBefore =
          values(c, "SELECT to_jsonb(t)::text FROM user_hobbies t ORDER BY user_hobby_id");
    }
    assertThat(flyway("16").migrate().migrationsExecuted).isEqualTo(1);
    flyway("16").validate();
    try (var c = migrationConnection()) {
      assertThat(values(c, "SELECT to_jsonb(a)::text FROM activities a ORDER BY activity_id"))
          .isEqualTo(activitiesBefore);
      assertThat(
              values(
                  c,
                  "SELECT to_jsonb(r)::text FROM activity_records r ORDER BY activity_record_id"))
          .isEqualTo(recordsBefore);
      execute(
          c,
          """
          INSERT INTO experiences(experience_id,experience_type,title,listing_status,hobby_id,linked_activity_id,capacity)
            VALUES (9101,'CLASS','V16 capped fixture','OPEN',9001,9001,8),
                   (9102,'CLASS','V16 uncapped fixture','OPEN',9001,9001,NULL);
          INSERT INTO experience_sessions(session_id,experience_id,starts_at,duration_minutes)
            VALUES (9101,9101,'2030-01-01Z',60),(9102,9102,'2030-01-02Z',45);
          """);
    }
    assertThat(flyway("17").migrate().migrationsExecuted).isEqualTo(1);
    flyway("17").validate();
    List<String> experiencesBefore, sessionsBefore;
    try (var c = migrationConnection()) {
      assertThat(
              values(
                  c,
                  "SELECT COALESCE(capacity::text,'NULL') FROM experience_sessions ORDER BY session_id"))
          .containsExactly("8", "NULL");
      assertThat(
              values(
                  c,
                  "SELECT materials::text || '|' || (cancellation_policy_url IS NULL)::text FROM experiences ORDER BY experience_id"))
          .containsExactly("[]|true", "[]|true");
      execute(
          c,
          """
          UPDATE experiences SET materials='["실제 V17 준비물"]'::jsonb,
            cancellation_policy_url='https://example.com/policy' WHERE experience_id=9101;
          UPDATE experience_sessions SET capacity=3 WHERE session_id=9101;
          """);
      experiencesBefore =
          values(c, "SELECT to_jsonb(e)::text FROM experiences e ORDER BY experience_id");
      sessionsBefore =
          values(c, "SELECT to_jsonb(s)::text FROM experience_sessions s ORDER BY session_id");
    }
    var migration = flyway("18");
    assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
    migration.validate();
    try (var c = migrationConnection()) {
      assertThat(values(c, "SELECT to_jsonb(e)::text FROM experiences e ORDER BY experience_id"))
          .isEqualTo(experiencesBefore);
      assertThat(
              values(c, "SELECT to_jsonb(s)::text FROM experience_sessions s ORDER BY session_id"))
          .isEqualTo(sessionsBefore);
      assertThat(
              values(
                  c,
                  "SELECT (to_jsonb(a)-'thumbnail_url'-'content_version'-'content_updated_at'-'content_change_xid')::text FROM activities a ORDER BY activity_id"))
          .isEqualTo(activitiesBefore);
      assertThat(
              values(
                  c,
                  "SELECT (to_jsonb(s)-'title'-'is_required'-'image_url')::text FROM activity_steps s ORDER BY activity_step_id"))
          .isEqualTo(stepsBefore);
      assertThat(
              values(
                  c,
                  "SELECT to_jsonb(m)::text FROM activity_materials m ORDER BY activity_material_id"))
          .isEqualTo(materialsBefore);
      assertThat(
              values(
                  c,
                  "SELECT to_jsonb(r)::text FROM activity_records r ORDER BY activity_record_id"))
          .isEqualTo(recordsBefore);
      assertThat(
              values(
                  c,
                  "SELECT to_jsonb(s)::text FROM saved_activities s ORDER BY user_id,activity_id"))
          .isEqualTo(savedBefore);
      assertThat(values(c, "SELECT to_jsonb(t)::text FROM user_hobbies t ORDER BY user_hobby_id"))
          .isEqualTo(treesBefore);
      assertThat(
              values(
                  c,
                  "SELECT content_version || '|' || (content_updated_at IS NULL)::text || '|' || (thumbnail_url IS NULL)::text FROM activities WHERE activity_id=9001"))
          .containsExactly("1|true|true");
      assertThat(
              values(
                  c,
                  "SELECT (title IS NULL AND is_required IS NULL AND image_url IS NULL)::text FROM activity_steps"))
          .containsExactly("true");
      assertThat(values(c, "SELECT (acquisition_type IS NULL)::text FROM activity_materials"))
          .containsExactly("true");
      execute(c, "UPDATE activities SET updated_at=clock_timestamp() WHERE activity_id=9001");
      assertThat(
              values(
                  c,
                  "SELECT (content_updated_at IS NULL)::text FROM activities WHERE activity_id=9001"))
          .containsExactly("true");
      execute(
          c, "UPDATE activity_steps SET title='담당자 승인 제목',is_required=true WHERE activity_id=9001");
      assertThat(
              values(
                  c,
                  "SELECT content_version || '|' || (content_updated_at > '2020-02-01Z'::timestamptz)::text FROM activities WHERE activity_id=9001"))
          .containsExactly("2|true");
    }
    assertThat(flyway("18").migrate().migrationsExecuted).isZero();
  }

  @Test
  void userHobbyUpdatedAtBackfillsOnlyKnownEvidenceAndPreservesExistingTreeAndActivityData()
      throws Exception {
    flyway("10").migrate();
    try (var connection = migrationConnection()) {
      execute(
          connection,
          "INSERT INTO users(user_id,provider,provider_user_id,account_status) VALUES (2001,'KAKAO','tree-updated-fixture','ACTIVE')");
      execute(
          connection,
          "INSERT INTO user_preferences(user_id,notification_frequency,updated_at) VALUES (2001,'WEEKLY','2020-01-02T00:00:00Z')");
      for (int id = 2001; id <= 2005; id++) {
        execute(
            connection,
            "INSERT INTO hobbies(hobby_id,hobby_code,hobby_name) VALUES ("
                + id
                + ",'tree-updated-"
                + id
                + "','Fixture')");
      }
      execute(
          connection,
          """
          INSERT INTO user_hobbies(user_hobby_id,user_id,hobby_id,tree_status,current_stage_number,planted_at,paused_at,ended_at) VALUES
          (2001,2001,2001,'ACTIVE',0,'2020-01-01Z',null,null),
          (2002,2001,2002,'PAUSED',3,'2020-01-01Z','2020-02-01Z',null),
          (2003,2001,2003,'ENDED',5,'2020-01-01Z','2020-02-01Z','2020-03-01Z'),
          (2004,2001,2004,'ACTIVE',2,'2020-01-01Z',null,null),
          (2005,2001,2005,'ENDED',4,'2020-01-01Z',null,null);
          INSERT INTO hobby_stages(hobby_stage_id,hobby_id,stage_number,stage_name) VALUES (2001,2001,0,'Fixture');
          INSERT INTO activities(activity_id,hobby_stage_id,activity_code,activity_name) VALUES (2001,2001,'tree-updated-fixture','Fixture');
          INSERT INTO activity_records(user_hobby_id,activity_id) VALUES (2001,2001);
          """);
    }
    List<String> treesBefore;
    List<String> recordsBefore;
    try (var connection = migrationConnection()) {
      treesBefore =
          values(connection, "SELECT to_jsonb(t)::text FROM user_hobbies t ORDER BY user_hobby_id");
      recordsBefore =
          values(
              connection,
              "SELECT to_jsonb(r)::text FROM activity_records r ORDER BY activity_record_id");
    }
    assertThat(flyway("11").migrate().migrationsExecuted).isEqualTo(1);
    try (var connection = migrationConnection()) {
      assertThat(
              values(
                  connection,
                  "SELECT notification_frequency || '|' || preferences_version || '|' || preferred_category_ids::text FROM user_preferences WHERE user_id=2001"))
          .containsExactly("WEEKLY|0|[]");
      assertThat(
              values(
                  connection,
                  "SELECT updated_at='2020-01-02T00:00:00Z'::timestamptz FROM user_preferences WHERE user_id=2001"))
          .containsExactly("t");
      execute(
          connection,
          """
          UPDATE user_preferences SET options_version='onboarding-v1',budget_level='MEDIUM',available_time_minutes=30,
            indoor_outdoor='ANY',difficulty_preference='BEGINNER',preferred_category_ids='[1,2]'::jsonb,preferences_version=7 WHERE user_id=2001
          """);
    }
    assertThat(flyway("12").migrate().migrationsExecuted).isEqualTo(1);
    List<String> preferencesBefore;
    List<String> usersBefore;
    List<String> agreementsBefore;
    try (var connection = migrationConnection()) {
      execute(
          connection,
          """
          UPDATE users SET onboarding_completed_at='2020-01-03T00:00:00Z' WHERE user_id=2001;
          INSERT INTO user_terms_agreements(user_id,terms_id,version,agreed,recorded_at)
            VALUES (2001,1,'1.0',true,'2020-01-03T00:00:00Z');
          """);
      preferencesBefore =
          values(
              connection,
              "SELECT to_jsonb(p)::text FROM user_preferences p ORDER BY preference_id");
      usersBefore = values(connection, "SELECT to_jsonb(u)::text FROM users u ORDER BY user_id");
      agreementsBefore =
          values(
              connection,
              "SELECT to_jsonb(t)::text FROM user_terms_agreements t ORDER BY user_terms_agreement_id");
      assertThat(
              values(
                  connection,
                  "SELECT count(*) FROM information_schema.columns WHERE table_name='user_hobbies' AND column_name='updated_at'"))
          .containsExactly("0");
    }
    assertThat(flyway("13").migrate().migrationsExecuted).isEqualTo(1);
    flyway("13").validate();
    assertThat(flyway("13").info().applied())
        .extracting(info -> info.getVersion().toString())
        .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13");
    try (var connection = migrationConnection()) {
      assertThat(
              values(
                  connection,
                  "SELECT to_jsonb(p)::text FROM user_preferences p ORDER BY preference_id"))
          .isEqualTo(preferencesBefore);
      assertThat(values(connection, "SELECT to_jsonb(u)::text FROM users u ORDER BY user_id"))
          .isEqualTo(usersBefore);
      assertThat(
              values(
                  connection,
                  "SELECT to_jsonb(t)::text FROM user_terms_agreements t ORDER BY user_terms_agreement_id"))
          .isEqualTo(agreementsBefore);
      assertThat(
              values(
                  connection,
                  "SELECT (to_jsonb(t)-'updated_at')::text FROM user_hobbies t ORDER BY user_hobby_id"))
          .isEqualTo(treesBefore);
      assertThat(
              values(
                  connection,
                  "SELECT to_jsonb(r)::text FROM activity_records r ORDER BY activity_record_id"))
          .isEqualTo(recordsBefore);
      assertThat(
              values(
                  connection,
                  "SELECT to_char(updated_at AT TIME ZONE 'UTC','YYYY-MM-DD') FROM user_hobbies ORDER BY user_hobby_id"))
          .containsExactly("2020-01-01", "2020-02-01", "2020-03-01", "2020-01-01", "2020-01-01");
      // A resumed ACTIVE tree (2004) is indistinguishable from an untouched one; not fake history.
      assertThat(
              values(
                  connection,
                  "SELECT is_nullable FROM information_schema.columns WHERE table_name='user_hobbies' AND column_name='updated_at'"))
          .containsExactly("NO");
    }
    assertThat(flyway("13").migrate().migrationsExecuted).isZero();
  }

  @Test
  void userHobbyUpdatedAtDefaultAndTriggerWorkWithRestrictedAppWritesAndSkipSameValueChanges()
      throws Exception {
    assertThat(flyway("13").migrate().migrationsExecuted).isEqualTo(13);
    grantAppAccess();
    try (var connection = migrationConnection()) {
      execute(
          connection,
          """
          INSERT INTO users(user_id,provider,provider_user_id,account_status) VALUES (2001,'KAKAO','tree-updated-write-fixture','ACTIVE');
          INSERT INTO hobbies(hobby_id,hobby_code,hobby_name) VALUES (2001,'tree-updated-write-fixture','Fixture');
          """);
    }
    try (var connection = appConnection()) {
      var created =
          values(
                  connection,
                  "INSERT INTO user_hobbies(user_id,hobby_id) VALUES (2001,2001) RETURNING updated_at::text")
              .getFirst();
      assertThat(created).isNotBlank();
      assertThat(
              values(
                  connection,
                  "UPDATE user_hobbies SET tree_status=tree_status RETURNING updated_at::text"))
          .containsExactly(created);
      assertThat(
              values(
                  connection,
                  "UPDATE user_hobbies SET updated_at='2000-01-01Z' RETURNING updated_at::text"))
          .containsExactly(created);
      var changed =
          values(
                  connection,
                  "UPDATE user_hobbies SET current_stage_number=1 RETURNING updated_at::text")
              .getFirst();
      assertThat(
              values(
                  connection,
                  "SELECT updated_at > '" + created + "'::timestamptz FROM user_hobbies"))
          .containsExactly("t");
      assertThat(changed).isNotEqualTo(created);
    }
    try (var connection = migrationConnection()) {
      assertThat(
              assertThrows(
                      SQLException.class,
                      () ->
                          execute(
                              connection,
                              "INSERT INTO user_hobbies(user_id,hobby_id,updated_at) VALUES (2001,2001,NULL)"))
                  .getSQLState())
          .isEqualTo("23502");
    }
  }

  @Test
  void userHobbyUpdatedAtLockTimeoutRollsBackColumnBackfillAndTriggerAndCanBeRetried()
      throws Exception {
    flyway("12").migrate();
    try (var reader = migrationConnection()) {
      reader.setAutoCommit(false);
      values(reader, "SELECT * FROM user_hobbies");
      var blocked =
          Flyway.configure()
              .configuration(flyway("13").getConfiguration())
              .initSql("SET lock_timeout='400ms'; SET statement_timeout='5s'")
              .load();
      assertMigrationSqlState(blocked, "55P03");
      try (var connection = migrationConnection()) {
        assertThat(
                values(
                    connection,
                    "SELECT count(*) FROM information_schema.columns WHERE table_name='user_hobbies' AND column_name='updated_at'"))
            .containsExactly("0");
        assertThat(
                values(
                    connection,
                    "SELECT count(*) FROM pg_trigger WHERE tgname='user_hobbies_updated_at_before_update'"))
            .containsExactly("0");
        assertThat(
                values(connection, "SELECT count(*) FROM flyway_schema_history WHERE version='13'"))
            .containsExactly("0");
      }
      reader.rollback();
    }
    assertThat(flyway("13").migrate().migrationsExecuted).isEqualTo(1);
    flyway("13").validate();
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static List<String> values(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      List<String> result = new ArrayList<>();
      while (rows.next()) result.add(rows.getString(1));
      return result;
    }
  }

  private void assertHistory(int version) throws Exception {
    // V3/V4 fixtures deliberately have pending files. Applied checksums must still validate.
    var migration = flyway("5");
    if (version < 5) {
      migration =
          Flyway.configure()
              .configuration(migration.getConfiguration())
              .ignoreMigrationPatterns("*:pending")
              .load();
    }
    migration.validate();
    MigrationInfo[] applied = migration.info().applied();
    assertThat(applied).hasSize(version);
    try (var connection = migrationConnection()) {
      assertThat(values(connection, "SELECT count(*) FROM flyway_schema_history WHERE NOT success"))
          .containsExactly("0");
      assertThat(values(connection, "SELECT DISTINCT installed_by FROM flyway_schema_history"))
          .containsExactly(migrationUser);
      for (int index = 0; index < version; index++) {
        var info = applied[index];
        assertThat(info.getVersion().toString()).isEqualTo("" + (index + 1));
        assertThat(info.getScript()).isEqualTo(SCRIPTS.get(index));
        assertThat(info.getChecksum()).isNotNull();
        assertThat(
                values(
                    connection,
                    "SELECT checksum FROM flyway_schema_history WHERE version='"
                        + info.getVersion()
                        + "'"))
            .containsExactly(info.getChecksum().toString());
      }
    }
  }

  private void seedV3Data() throws Exception {
    try (var connection = migrationConnection()) {
      execute(
          connection,
          """
          INSERT INTO users(user_id,provider,provider_user_id,nickname,account_status)
            VALUES (1001,'GOOGLE','fixture-user','Fixture','ACTIVE');
          INSERT INTO user_preferences(user_id) VALUES (1001);
          INSERT INTO user_locations(user_id,region_sido,region_sigungu) VALUES (1001,'Fixture','Fixture');
          INSERT INTO location_consent_history(user_id,consent_action,terms_version) VALUES (1001,'GRANTED','fixture');
          INSERT INTO hobbies(hobby_id,hobby_code,hobby_name) VALUES (1001,'fixture','Fixture');
          INSERT INTO hobby_stages(hobby_stage_id,hobby_id,stage_number,stage_name) VALUES (1001,1001,0,'Fixture');
          INSERT INTO activities(activity_id,hobby_stage_id,activity_code,activity_name,review_status)
            VALUES (1001,1001,'fixture','Fixture','PUBLISHED');
          INSERT INTO activity_steps(activity_id,step_number,instruction) VALUES (1001,1,'Fixture');
          INSERT INTO activity_materials(activity_id,material_name) VALUES (1001,'Fixture');
          INSERT INTO user_hobbies(user_hobby_id,user_id,hobby_id) VALUES (1001,1001,1001);
          INSERT INTO activity_records(user_hobby_id,activity_id) VALUES (1001,1001);
          INSERT INTO recommendations(recommendation_id,user_id,preference_snapshot,recommendation_version)
            VALUES (1001,1001,'{}','fixture');
          INSERT INTO recommendation_items(recommendation_id,hobby_id,first_activity_id,rank_order,recommendation_reason)
            VALUES (1001,1001,1001,1,'Fixture');
          INSERT INTO user_notifications(user_id,notification_type,title,message) VALUES (1001,'FIXTURE','Fixture','Fixture');
          INSERT INTO saved_activities(user_id,activity_id) VALUES (1001,1001);
          INSERT INTO experiences(experience_id,experience_type,title,listing_status,linked_activity_id,hobby_id)
            VALUES (1001,'CLASS','Fixture DRAFT','DRAFT',1001,1001),
                   (1002,'CLASS','Fixture OPEN','OPEN',1001,1001),
                   (1003,'CLASS','Fixture CLOSED','CLOSED',1001,1001),
                   (1004,'CLASS','Fixture CANCELLED','CANCELLED',1001,1001);
          INSERT INTO experience_reservations(reservation_code,experience_id,user_id) VALUES ('fixture',1002,1001);
          INSERT INTO notification_subscriptions(user_id,subscription_type,experience_id) VALUES (1001,'CLASS_OPEN',1001);
          """);
    }
  }

  private Map<String, List<String>> existingData() throws Exception {
    Map<String, List<String>> result = new LinkedHashMap<>();
    try (var connection = migrationConnection()) {
      for (String table :
          values(
              connection,
              "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history','content_reports') ORDER BY tablename")) {
        assertThat(table).matches("[a-z_]+");
        result.put(
            table,
            values(
                connection,
                "SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY to_jsonb(t)::text"));
      }
    }
    return result;
  }

  private void grantAppAccess() throws Exception {
    try (var connection = migrationConnection()) {
      execute(connection, "GRANT USAGE ON SCHEMA public TO " + appUser);
      for (String table :
          values(
              connection,
              "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history'")) {
        assertThat(table).matches("[a-z_]+");
        execute(connection, "GRANT SELECT, INSERT, UPDATE, DELETE ON " + table + " TO " + appUser);
      }
      execute(connection, "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + appUser);
    }
  }

  private List<String> existingSchema() throws Exception {
    try (var connection = migrationConnection()) {
      return values(
          connection,
          """
          SELECT 'column:' || row_to_json(c)::text
          FROM information_schema.columns c
          WHERE table_schema='public' AND table_name NOT IN ('flyway_schema_history','content_reports')
          UNION ALL
          SELECT 'index:' || indexdef FROM pg_indexes
          WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history','content_reports')
          UNION ALL
          SELECT 'constraint:' || c.conrelid::regclass::text || ':' || c.conname || ':' || pg_get_constraintdef(c.oid)
          FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
          WHERE n.nspname='public' AND t.relname NOT IN ('flyway_schema_history','content_reports')
            AND NOT (t.relname='experiences' AND c.conname='experiences_listing_status_check')
          ORDER BY 1
          """);
    }
  }

  private static void assertMigrationSqlState(Flyway migration, String expected) {
    Throwable cause = assertThrows(FlywayException.class, migration::migrate);
    while (cause.getCause() != null) cause = cause.getCause();
    assertThat(cause).isInstanceOf(SQLException.class);
    assertThat(((SQLException) cause).getSQLState()).isEqualTo(expected);
  }

  @Test
  void freshDatabaseRecordsAllChecksumsAndSecondMigrateIsEmpty() throws Exception {
    var migration = flyway("5");
    assertThat(migration.migrate().migrationsExecuted).isEqualTo(5);
    migration.validate();
    assertHistory(5);
    assertThat(migration.info().pending()).isEmpty();
    assertThat(migration.migrate().migrationsExecuted).isZero();
    assertHistory(5);
    for (var info : migration.info().applied()) {
      System.out.println("MIGRATION_CHECKSUM V" + info.getVersion() + "=" + info.getChecksum());
    }
  }

  @Test
  void v3UpgradePreservesAllEighteenPopulatedTablesAndHistory() throws Exception {
    flyway("3").migrate();
    seedV3Data();
    var before = existingData();
    var schemaBefore = existingSchema();
    assertThat(before).hasSize(18).allSatisfy((table, rows) -> assertThat(rows).isNotEmpty());
    assertHistory(3);
    assertThat(flyway("5").migrate().migrationsExecuted).isEqualTo(2);
    assertThat(existingData()).isEqualTo(before);
    assertThat(existingSchema()).isEqualTo(schemaBefore);
    assertHistory(5);
    assertThat(flyway("5").migrate().migrationsExecuted).isZero();
  }

  @Test
  void reportsCatalogAndSeparateAppPrivilegesAreExplicit() throws Exception {
    flyway("3").migrate();
    seedV3Data();
    grantAppAccess();
    flyway("5").migrate();
    try (var connection = migrationConnection()) {
      assertThat(
              values(
                  connection,
                  "SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='content_reports'"))
          .containsExactly(migrationUser);
      assertThat(
              values(
                  connection,
                  "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='content_reports' AND is_nullable='NO' ORDER BY ordinal_position"))
          .containsExactly(
              "report_id",
              "reporter_user_id",
              "target_type",
              "target_id",
              "reason",
              "description",
              "status",
              "created_at");
      assertThat(
              values(
                  connection,
                  "SELECT indexname FROM pg_indexes WHERE schemaname='public' AND tablename='content_reports' ORDER BY indexname"))
          .containsExactly("content_reports_pkey", "idx_reports_dedupe");
      assertThat(
              values(
                      connection,
                      "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND indexname='idx_reports_dedupe'")
                  .getFirst())
          .contains("(reporter_user_id, target_type, target_id, reason, created_at DESC)")
          .doesNotContain("UNIQUE");
      assertThat(
              values(
                  connection,
                  "SELECT is_identity FROM information_schema.columns WHERE table_schema='public' AND table_name='content_reports' AND column_name='report_id'"))
          .containsExactly("YES");
      assertThat(
              values(
                      connection,
                      "SELECT column_default FROM information_schema.columns WHERE table_schema='public' AND table_name='content_reports' AND column_name='status'")
                  .getFirst())
          .contains("RECEIVED");
    }
    try (var connection = appConnection()) {
      assertThat(
              assertThrows(
                      SQLException.class,
                      () -> values(connection, "SELECT count(*) FROM content_reports"))
                  .getSQLState())
          .isEqualTo("42501");
    }
    try (var connection = migrationConnection()) {
      execute(connection, "GRANT SELECT, INSERT ON content_reports TO " + appUser);
    }
    try (var connection = appConnection()) {
      assertThat(
              values(
                  connection,
                  "SELECT has_table_privilege(current_user,'flyway_schema_history','INSERT')"))
          .containsExactly("f");
      assertThat(
              values(
                  connection,
                  "SELECT has_sequence_privilege(current_user,pg_get_serial_sequence('content_reports','report_id'),'USAGE')"))
          .containsExactly("f");
      assertThat(
              insertReport(connection, 1001L, "ACTIVITY", 1001L, "OTHER", "abcdefghij", "RECEIVED"))
          .isPositive();
      assertThat(
              assertThrows(
                      SQLException.class,
                      () ->
                          values(
                              connection,
                              "SELECT nextval(pg_get_serial_sequence('content_reports','report_id'))"))
                  .getSQLState())
          .isEqualTo("42501");
    }
  }

  private static long insertReport(
      Connection connection,
      Long reporter,
      String type,
      Long target,
      String reason,
      String description,
      String status)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "INSERT INTO content_reports(reporter_user_id,target_type,target_id,reason,description,status) VALUES (?,?,?,?,?,?) RETURNING report_id")) {
      Object[] arguments = {reporter, type, target, reason, description, status};
      for (int index = 0; index < arguments.length; index++)
        statement.setObject(index + 1, arguments[index]);
      try (var rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  @Test
  void reportChecksForeignKeyAndCascadeMatchTheServiceBoundary() throws Exception {
    flyway("3").migrate();
    seedV3Data();
    flyway("5").migrate();
    try (var connection = migrationConnection()) {
      for (String reason : List.of("WRONG_INFORMATION", "BROKEN_LINK", "INAPPROPRIATE", "OTHER")) {
        assertThat(
                insertReport(
                    connection, 1001L, "ACTIVITY", 1001L, reason, "abcdefghij", "RECEIVED"))
            .isPositive();
      }
      assertThat(
              insertReport(
                  connection,
                  1001L,
                  "SPONSORED_CONTENT",
                  999999L,
                  "OTHER",
                  "가".repeat(1000),
                  "RECEIVED"))
          .isPositive();
      // Target existence and 24h deduplication are service rules, not database constraints.
      assertThat(
              insertReport(
                  connection, 1001L, "ACTIVITY", 1001L, "OTHER", "😀".repeat(10), "RECEIVED"))
          .isPositive();
      assertThat(
              assertThrows(
                      SQLException.class,
                      () ->
                          insertReport(
                              connection,
                              999999L,
                              "ACTIVITY",
                              1L,
                              "OTHER",
                              "abcdefghij",
                              "RECEIVED"))
                  .getSQLState())
          .isEqualTo("23503");
      assertThat(
              assertThrows(
                      SQLException.class,
                      () ->
                          insertReport(
                              connection, null, "ACTIVITY", 1L, "OTHER", "abcdefghij", "RECEIVED"))
                  .getSQLState())
          .isEqualTo("23502");
      for (String description : List.of("abcdefghi", "   short   ", "😀".repeat(5))) {
        assertThat(
                assertThrows(
                        SQLException.class,
                        () ->
                            insertReport(
                                connection,
                                1001L,
                                "ACTIVITY",
                                1L,
                                "OTHER",
                                description,
                                "RECEIVED"))
                    .getSQLState())
            .isEqualTo("23514");
      }
      assertThat(
              assertThrows(
                      SQLException.class,
                      () ->
                          insertReport(
                              connection,
                              1001L,
                              "ACTIVITY",
                              1L,
                              "OTHER",
                              "x".repeat(1001),
                              "RECEIVED"))
                  .getSQLState())
          .isEqualTo("22001");
      assertThat(
              assertThrows(
                      SQLException.class,
                      () ->
                          insertReport(
                              connection, 1001L, "USER", 1L, "OTHER", "abcdefghij", "RECEIVED"))
                  .getSQLState())
          .isEqualTo("23514");
      assertThat(
              assertThrows(
                      SQLException.class,
                      () ->
                          insertReport(
                              connection, 1001L, "ACTIVITY", 1L, "SPAM", "abcdefghij", "RECEIVED"))
                  .getSQLState())
          .isEqualTo("23514");
      assertThat(
              assertThrows(
                      SQLException.class,
                      () ->
                          insertReport(
                              connection, 1001L, "ACTIVITY", 1L, "OTHER", "abcdefghij", "RESOLVED"))
                  .getSQLState())
          .isEqualTo("23514");
      execute(connection, "DELETE FROM users WHERE user_id=1001");
      assertThat(values(connection, "SELECT count(*) FROM content_reports")).containsExactly("0");
    }
  }

  @Test
  void comingSoonAndCurrentHibernateValidateAcceptTheUpgradedSchema() throws Exception {
    flyway("3").migrate();
    seedV3Data();
    flyway("4").migrate();
    try (var connection = migrationConnection()) {
      assertThat(
              assertThrows(
                      SQLException.class,
                      () ->
                          execute(
                              connection,
                              "INSERT INTO experiences(experience_type,title,listing_status) VALUES ('CLASS','Fixture','COMING_SOON')"))
                  .getSQLState())
          .isEqualTo("23514");
    }
    flyway("5").migrate();
    try (var connection = migrationConnection()) {
      execute(
          connection,
          "INSERT INTO experiences(experience_type,title,listing_status) VALUES ('CLASS','Fixture','COMING_SOON')");
      assertThat(
              values(connection, "SELECT listing_status FROM experiences ORDER BY listing_status"))
          .containsExactly("CANCELLED", "CLOSED", "COMING_SOON", "DRAFT", "OPEN");
      for (String value : List.of("UNKNOWN", "HIDDEN")) {
        assertThat(
                assertThrows(
                        SQLException.class,
                        () ->
                            execute(
                                connection,
                                "UPDATE experiences SET listing_status='"
                                    + value
                                    + "' WHERE experience_id=1001"))
                    .getSQLState())
            .isEqualTo("23514");
      }
      assertThat(
              assertThrows(
                      SQLException.class,
                      () ->
                          execute(
                              connection,
                              "UPDATE experiences SET listing_status=NULL WHERE experience_id=1001"))
                  .getSQLState())
          .isEqualTo("23502");
      for (String assignment :
          List.of(
              "capacity=0",
              "price_amount=-1",
              "booking_mode='UNKNOWN'",
              "experience_type='UNKNOWN'",
              "starts_at=now(),ends_at=now()-interval '1 hour'")) {
        assertThat(
                assertThrows(
                        SQLException.class,
                        () ->
                            execute(
                                connection,
                                "UPDATE experiences SET "
                                    + assignment
                                    + " WHERE experience_id=1001"))
                    .getSQLState())
            .isEqualTo("23514");
      }
    }
    // "Current" Hibernate maps whatever the latest migration added (V6's sponsored_contents/
    // activity_sponsored_contents today), so the schema under validate must go all the way to
    // the newest migration on the classpath, not a version pinned at the time this test was
    // written - otherwise the entity scan below fails with SchemaManagementException once a
    // later migration adds another mapped table. "latest" is a real Flyway target value.
    flyway("latest").migrate();
    grantAppAccess();
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", databaseUrl)
            .applySetting("hibernate.connection.username", appUser)
            .applySetting("hibernate.connection.password", fixturePassword)
            .applySetting("hibernate.connection.pool_size", 2)
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .applySetting("hibernate.default_schema", "public")
            .build();
    try {
      var metadata = new MetadataSources(registry);
      var scanner = new ClassPathScanningCandidateComponentProvider(false);
      scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
      for (var bean : scanner.findCandidateComponents("com.hobbytree.hobbytree.domain")) {
        metadata.addAnnotatedClass(Class.forName(bean.getBeanClassName()));
      }
      try (var factory = metadata.buildMetadata().buildSessionFactory();
          var session = factory.openSession()) {
        assertThat(
                session
                    .createQuery("select e.listingStatus from Experience e", ListingStatus.class)
                    .getResultList())
            .containsExactlyInAnyOrder(ListingStatus.values());
      }
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }

  @Test
  void v5LockTimeoutRollsBackBothDdlAndHistoryAndCanBeRetried() throws Exception {
    flyway("4").migrate();
    try (var reader = migrationConnection()) {
      reader.setAutoCommit(false);
      values(reader, "SELECT * FROM experiences");
      var blocked =
          Flyway.configure()
              .configuration(flyway("5").getConfiguration())
              .initSql("SET lock_timeout='400ms'; SET statement_timeout='5s'")
              .load();
      assertMigrationSqlState(blocked, "55P03");
      assertHistory(4);
      reader.rollback();
    }
    try (var connection = migrationConnection()) {
      assertThat(
              values(
                      connection,
                      "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='experiences'::regclass AND conname='experiences_listing_status_check'")
                  .getFirst())
          .doesNotContain("COMING_SOON");
    }
    assertThat(flyway("5").migrate().migrationsExecuted).isEqualTo(1);
    assertHistory(5);
  }

  private static String migrationSql(String name) throws Exception {
    try (var input = FlywayMigrationTests.class.getResourceAsStream("/db/migration/" + name)) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void v4ForeignKeyLocksUsersAgainstWritersUntilTransactionEnds() throws Exception {
    flyway("3").migrate();
    seedV3Data();
    try (var ddl = migrationConnection();
        var writer = migrationConnection()) {
      ddl.setAutoCommit(false);
      execute(ddl, migrationSql(SCRIPTS.get(3)));
      assertThat(
              values(
                  ddl,
                  "SELECT mode FROM pg_locks WHERE pid=pg_backend_pid() AND relation='users'::regclass AND granted"))
          .contains("ShareRowExclusiveLock");
      execute(writer, "SET lock_timeout='400ms'");
      assertThat(
              assertThrows(
                      SQLException.class,
                      () ->
                          execute(
                              writer,
                              "UPDATE users SET nickname='Fixture changed' WHERE user_id=1001"))
                  .getSQLState())
          .isEqualTo("55P03");
      ddl.rollback();
    }
    assertHistory(3);
    assertThat(flyway("5").migrate().migrationsExecuted).isEqualTo(2);
    assertHistory(5);
  }

  @Test
  void changedFixtureChecksumIsRejectedWithoutChangingOriginalSql(@TempDir Path copy)
      throws Exception {
    flyway("5").migrate();
    for (String name : SCRIPTS)
      Files.writeString(copy.resolve(name), migrationSql(name), StandardCharsets.UTF_8);
    Files.writeString(
        copy.resolve(SCRIPTS.get(3)),
        migrationSql(SCRIPTS.get(3)) + "\n-- isolated checksum fixture\n",
        StandardCharsets.UTF_8);
    var changed =
        Flyway.configure()
            .configuration(flyway("5").getConfiguration())
            .locations("filesystem:" + copy.toAbsolutePath())
            .load();
    var validation = assertThrows(FlywayException.class, changed::validate);
    assertThat(validation.getMessage()).containsIgnoringCase("checksum mismatch");
    assertHistory(5);
    assertThat(flyway("5").migrate().migrationsExecuted).isZero();
  }

  @Test
  void unexpectedObjectsAndInvalidExistingDataFailWithoutRepair() throws Exception {
    flyway("3").migrate();
    try (var connection = migrationConnection()) {
      execute(connection, "CREATE TABLE content_reports(fixture_id integer)");
      assertMigrationSqlState(flyway("4"), "42P07");
      assertHistory(3);
      execute(connection, "DROP TABLE content_reports");
      flyway("4").migrate();
      execute(
          connection, "ALTER TABLE experiences DROP CONSTRAINT experiences_listing_status_check");
      assertMigrationSqlState(flyway("5"), "42704");
      assertHistory(4);
      execute(
          connection,
          "ALTER TABLE experiences ADD CONSTRAINT experiences_listing_status_check CHECK (listing_status IN ('DRAFT','OPEN','CLOSED','CANCELLED','UNKNOWN'))");
      execute(
          connection,
          "INSERT INTO experiences(experience_type,title,listing_status) VALUES ('CLASS','Invalid fixture','UNKNOWN')");
      assertMigrationSqlState(flyway("5"), "23514");
      assertHistory(4);
      assertThat(
              values(
                      connection,
                      "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='experiences'::regclass AND conname='experiences_listing_status_check'")
                  .getFirst())
          .contains("UNKNOWN");
      assertThat(
              values(connection, "SELECT count(*) FROM experiences WHERE listing_status='UNKNOWN'"))
          .containsExactly("1");
      execute(connection, "DELETE FROM experiences WHERE listing_status='UNKNOWN'");
      execute(
          connection, "ALTER TABLE experiences DROP CONSTRAINT experiences_listing_status_check");
      execute(
          connection,
          "ALTER TABLE experiences ADD CONSTRAINT experiences_listing_status_check CHECK (listing_status IN ('DRAFT','OPEN','CLOSED','CANCELLED'))");
    }
    assertThat(flyway("5").migrate().migrationsExecuted).isEqualTo(1);
    assertHistory(5);
  }
}
