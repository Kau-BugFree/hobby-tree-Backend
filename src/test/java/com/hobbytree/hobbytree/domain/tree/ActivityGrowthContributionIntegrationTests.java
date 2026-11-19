package com.hobbytree.hobbytree.domain.tree;

import static org.assertj.core.api.Assertions.assertThat;

import com.hobbytree.hobbytree.domain.tree.entity.GrowthContributionSource;
import com.hobbytree.hobbytree.domain.tree.repository.ActivityGrowthContributionRepository;
import com.hobbytree.hobbytree.domain.tree.repository.ActivityRecordRepository;
import com.hobbytree.hobbytree.support.GrowthLedgerFixtures;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class ActivityGrowthContributionIntegrationTests extends IntegrationTestSupport {
  @Autowired private ActivityGrowthContributionRepository ledger;
  @Autowired private ActivityRecordRepository records;

  @ParameterizedTest
  @CsvSource({"2,0,0,ACTIVE", "2,1,3,PAUSED", "4,2,9,ENDED", "3,5,17,ACTIVE"})
  void api18And20And22KeepLegacyGrowthValuesAndStoredStages(
      int required, int currentStage, int count, String status) throws Exception {
    var login = login();
    assertThat(login.statusCode()).isEqualTo(200);
    var signedIn = result(login);
    long user = signedIn.path("userId").asLong();
    createdUsers.add(user);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", user);
    var fixture = tree(user, required, currentStage, status);
    for (int i = 0; i < count; i++) {
      var record = record(fixture, Math.min(i / required, 5), 4);
      GrowthLedgerFixtures.copyCreditIfCounted(jdbc, fixture.treeId(), record.activityId());
    }
    var before = originalRecords(fixture.treeId());
    var oldCounts =
        jdbc.queryForMap(
            """
            SELECT count(*) AS total,
              count(*) FILTER(WHERE growth_stage_number=?) AS current_count
            FROM activity_records WHERE user_hobby_id=? AND growth_counted
            """,
            currentStage,
            fixture.treeId());
    long total = ((Number) oldCounts.get("total")).longValue();
    long current = ((Number) oldCounts.get("current_count")).longValue();
    String token = signedIn.path("accessToken").asText();
    var item = get(token, "/api/v1/user-hobbies").path("items").get(0);
    var detail = get(token, "/api/v1/user-hobbies/" + fixture.treeId());
    var roadmap = get(token, "/api/v1/user-hobbies/" + fixture.treeId() + "/roadmap");
    for (var response : List.of(item, detail, roadmap)) {
      assertThat(response.path("distinctCompletedActivityCount").asLong()).isEqualTo(total);
      assertThat(response.path("currentStageNumber").asInt()).isEqualTo(currentStage + 1);
    }
    assertThat(detail.path("currentStageCompletedCount").asLong()).isEqualTo(current);
    for (var response : List.of(item, detail)) {
      assertThat(response.path("remainingCountForNextStage").asLong())
          .isEqualTo(currentStage == 5 ? 0 : Math.max(0, required - current));
      assertThat(response.path("requiredCountForNextStage").isNull()).isEqualTo(currentStage == 5);
    }
    assertThat(roadmap.path("growthRule").path("distinctActivitiesPerStage").asInt())
        .isEqualTo(required);
    assertThat(roadmap.path("stages").get(0).path("unlockAtDistinctCompletedCount").asLong())
        .isZero();
    assertThat(originalRecords(fixture.treeId())).isEqualTo(before);
    assertThat(ledger.countLegacyMismatches()).isZero();
  }

  @Test
  void entityRetainsLegacyIdentityAttemptsAndUnknownRecognitionTime() {
    var fixture = tree(owner(), 3, 0, "ACTIVE");
    var record = record(fixture, 0, 7);
    GrowthLedgerFixtures.copyCreditIfCounted(jdbc, fixture.treeId(), record.activityId());
    var credit = ledger.findByOriginRecordId(record.id()).orElseThrow();
    assertThat(credit.getActivityId()).isEqualTo(record.activityId());
    assertThat(credit.getCreditedStageNumber()).isEqualTo((short) 0);
    assertThat(credit.getSource()).isEqualTo(GrowthContributionSource.LEGACY_RECORD);
    assertThat(credit.getCreditedAt()).isNull();
    assertThat(credit.getMigratedAt()).isNotNull();
    assertThat(
            ledger.findByUserHobby_UserHobbyIdAndActivityId(fixture.treeId(), record.activityId()))
        .isPresent();
    assertThat(
            jdbc.queryForObject(
                "SELECT attempt_count FROM activity_records WHERE activity_record_id=?",
                Integer.class,
                record.id()))
        .isEqualTo(7);
  }

  @Test
  void oldWriterDriftIsDetectableAndNeverAutoHealedOrHiddenByFallback() {
    var fixture = tree(owner(), 3, 0, "ACTIVE");
    var record =
        record(fixture, 0, 2); // Simulates an unsupported old/manual writer after backfill.
    var before = originalRecords(fixture.treeId());
    assertThat(records.countGrowthByTreeIds(List.of(fixture.treeId()))).isEmpty();
    assertThat(ledger.countLegacyMismatches()).isEqualTo(1);
    assertThat(ledger.findByOriginRecordId(record.id())).isEmpty();
    assertThat(originalRecords(fixture.treeId())).isEqualTo(before);
  }

  @Test
  void compatibleWriterCommitsOrRollsBackBothRepresentationsTogether() throws Exception {
    var fixture = tree(owner(), 3, 0, "ACTIVE");
    var record = record(fixture, null, 1);
    var before = originalRecords(fixture.treeId());
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      setCreditAndCopy(connection, record.id());
      assertThat(ledger.countLegacyMismatches()).isZero(); // Uncommitted changes are invisible.
      assertThat(records.countGrowthByTreeIds(List.of(fixture.treeId()))).isEmpty();
      connection.rollback();
    }
    assertThat(originalRecords(fixture.treeId())).isEqualTo(before);
    assertThat(ledger.findByOriginRecordId(record.id())).isEmpty();
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      setCreditAndCopy(connection, record.id());
      connection.commit();
    }
    assertThat(ledger.countLegacyMismatches()).isZero();
    var growth = records.countGrowthByTreeIds(List.of(fixture.treeId())).getFirst();
    assertThat(growth.getTotalCount()).isEqualTo(1);
    assertThat(growth.getCurrentStageCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void uniqueContributionWaitsForAnotherConnectionAndHandlesCommitOrRollback(boolean rollback)
      throws Exception {
    var fixture = tree(owner(), 3, 0, "ACTIVE");
    var record = record(fixture, 0, 1);
    try (var first = dataSource.getConnection();
        var second = dataSource.getConnection();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      first.setAutoCommit(false);
      int secondPid;
      try (var s = second.createStatement();
          var rows = s.executeQuery("SELECT pg_backend_pid()")) {
        rows.next();
        secondPid = rows.getInt(1);
      }
      assertThat(insertCredit(first, record.id())).isEqualTo(1);
      var started = new CountDownLatch(1);
      var pending =
          executor.submit(
              () -> {
                started.countDown();
                return insertCredit(second, record.id());
              });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      boolean waiting = false;
      while (System.nanoTime() < deadline) {
        waiting =
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT coalesce(wait_event_type='Lock',false) FROM pg_stat_activity WHERE pid=?",
                    Boolean.class,
                    secondPid));
        if (waiting) break;
        Thread.sleep(20);
      }
      assertThat(waiting)
          .as("second independent connection must wait for the first transaction")
          .isTrue();
      assertThat(pending.isDone()).isFalse();
      if (rollback) first.rollback();
      else first.commit();
      assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(rollback ? 1 : 0);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_growth_contributions WHERE origin_record_id=?",
                Long.class,
                record.id()))
        .isEqualTo(1);
  }

  private int insertCredit(Connection connection, long recordId) throws Exception {
    try (var statement =
        connection.prepareStatement(
            """
        INSERT INTO activity_growth_contributions(user_hobby_id,activity_id,origin_record_id,
          credited_stage_number,source,migrated_at)
        SELECT user_hobby_id,activity_id,activity_record_id,growth_stage_number,
          'LEGACY_RECORD',clock_timestamp() FROM activity_records WHERE activity_record_id=? AND growth_counted
        ON CONFLICT ON CONSTRAINT uq_growth_tree_activity DO NOTHING
        """)) {
      statement.setQueryTimeout(5);
      statement.setLong(1, recordId);
      return statement.executeUpdate();
    }
  }

  private void setCreditAndCopy(Connection connection, long recordId) throws Exception {
    try (var s =
        connection.prepareStatement(
            "UPDATE activity_records SET growth_counted=true,growth_stage_number=0 WHERE activity_record_id=?")) {
      s.setLong(1, recordId);
      assertThat(s.executeUpdate()).isEqualTo(1);
    }
    assertThat(insertCredit(connection, recordId)).isEqualTo(1);
  }

  private long owner() {
    long id =
        jdbc.queryForObject(
            "INSERT INTO users(provider,provider_user_id,account_status) VALUES('KAKAO',?,'ACTIVE') RETURNING user_id",
            Long.class,
            UUID.randomUUID().toString());
    createdUsers.add(id);
    return id;
  }

  private Fixture tree(long owner, int required, int current, String status) {
    long hobby =
        jdbc.queryForObject(
            "INSERT INTO hobbies(hobby_code,hobby_name,activities_per_stage) VALUES(?,'TEST ONLY',?) RETURNING hobby_id",
            Long.class,
            "profile-test-" + owner + "-" + UUID.randomUUID().toString().substring(0, 12),
            required);
    long stage =
        jdbc.queryForObject(
            "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES(?,0,'TEST ONLY') RETURNING hobby_stage_id",
            Long.class,
            hobby);
    long tree =
        jdbc.queryForObject(
            "INSERT INTO user_hobbies(user_id,hobby_id,current_stage_number,tree_status) VALUES(?,?,?,?) RETURNING user_hobby_id",
            Long.class,
            owner,
            hobby,
            current,
            status);
    return new Fixture(tree, hobby, stage);
  }

  private SeedRecord record(Fixture fixture, Integer stage, int attempts) {
    long activity =
        jdbc.queryForObject(
            "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status) VALUES(?,?,'TEST ONLY','PUBLISHED') RETURNING activity_id",
            Long.class,
            fixture.stageId(),
            UUID.randomUUID().toString());
    long id =
        jdbc.queryForObject(
            "INSERT INTO activity_records(user_hobby_id,activity_id,activity_status,growth_counted,growth_stage_number,attempt_count) VALUES(?,?,'COMPLETED',?,?,?) RETURNING activity_record_id",
            Long.class,
            fixture.treeId(),
            activity,
            stage != null,
            stage,
            attempts);
    return new SeedRecord(id, activity);
  }

  private List<String> originalRecords(long tree) {
    return jdbc.queryForList(
        "SELECT to_jsonb(r)::text FROM activity_records r WHERE user_hobby_id=? ORDER BY activity_record_id",
        String.class,
        tree);
  }

  private JsonNode get(String token, String path) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return result(response);
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update("DELETE FROM user_hobbies WHERE user_id=?", userId);
    jdbc.update(
        "DELETE FROM activities WHERE hobby_stage_id IN (SELECT s.hobby_stage_id FROM hobby_stages s JOIN hobbies h USING(hobby_id) WHERE h.hobby_code LIKE ?)",
        "profile-test-" + userId + "-%");
    super.cleanupSeededHobbies(userId);
  }

  private record Fixture(long treeId, long hobbyId, long stageId) {}

  private record SeedRecord(long id, long activityId) {}
}
