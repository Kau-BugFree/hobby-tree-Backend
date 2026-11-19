package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordProgressRepository;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class ActivityRecordProgressIntegrationTests extends IntegrationTestSupport {
  @MockitoSpyBean private ActivityRecordProgressRepository progressRecords;

  @Test
  void savesWholeSnapshotListAndReturnsPersistedVersionTimeAndSortedProgress() throws Exception {
    var f = fixture();
    var before = row(f);
    var response = save(f, body(List.of(f.optional(), f.first()), 1200, 1));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(code(response)).isEqualTo("RECORD200_3");
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    var saved = result(response);
    assertThat(saved.path("recordId").asLong()).isEqualTo(f.record());
    assertThat(saved.path("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(saved.path("version").asLong()).isEqualTo(2);
    assertThat(saved.path("elapsedSeconds").asInt()).isEqualTo(1200);
    assertThat(saved.path("progressPercent").asInt()).isEqualTo(33);
    assertThat(saved.path("completedStepIds").get(0).asLong()).isEqualTo(f.first());
    assertThat(saved.path("completedStepIds").get(1).asLong()).isEqualTo(f.optional());
    var after = row(f);
    assertThat(Instant.parse(saved.path("updatedAt").asText()))
        .isEqualTo(((java.sql.Timestamp) after.get("updated_at")).toInstant());
    assertThat(((java.sql.Timestamp) after.get("updated_at")).toInstant())
        .isAfter(((java.sql.Timestamp) before.get("updated_at")).toInstant());
    assertThat(after.get("started_at")).isEqualTo(before.get("started_at"));
    assertThat(
            result(get(f, "/api/v1/activity-records/" + f.record()))
                .path("progressPercent")
                .asInt())
        .isEqualTo(33);
    var tree = result(get(f, "/api/v1/user-hobbies/" + f.tree()));
    assertThat(tree.path("currentActivityRecord").path("progressPercent").asInt()).isEqualTo(33);
    assertThat(tree.path("currentActivityRecord").path("title").asText())
        .isEqualTo("TEST ONLY record28 original");
  }

  @Test
  void optionalStepsDoNotIncreaseProgressAndEmptyListUnchecksEverything() throws Exception {
    var f = fixture();
    assertThat(
            result(save(f, body(List.of(f.optional()), 1200, 1))).path("progressPercent").asInt())
        .isZero();
    var cleared = result(save(f, "{\"completedStepIds\":[],\"expectedVersion\":2}"));
    assertThat(cleared.path("completedStepIds").size()).isZero();
    assertThat(cleared.path("progressPercent").asInt()).isZero();
    assertThat(cleared.path("elapsedSeconds").asInt()).isEqualTo(1200);
    assertThat(cleared.path("version").asLong()).isEqualTo(3);
  }

  @Test
  void allowsReportedTimeDecreaseAndNullPreservesPreviousValue() throws Exception {
    var f = fixture();
    jdbc.update(
        "UPDATE activity_records SET actual_minutes=7 WHERE activity_record_id=?", f.record());
    assertThat(result(save(f, body(List.of(), 86400, 1))).path("elapsedSeconds").asInt())
        .isEqualTo(86400);
    assertThat(result(save(f, body(List.of(), 0, 2))).path("elapsedSeconds").asInt()).isZero();
    var saved =
        result(save(f, "{\"completedStepIds\":[],\"elapsedSeconds\":null,\"expectedVersion\":3}"));
    assertThat(saved.path("elapsedSeconds").asInt()).isZero();
    assertThat(saved.path("version").asLong()).isEqualTo(4);
    assertThat(row(f).get("actual_minutes")).isEqualTo(7);
  }

  @Test
  void everySuccessfulSaveIncrementsVersionEvenIfUnchangedAndStaleRetryChangesNothing()
      throws Exception {
    var f = fixture();
    var first = result(save(f, body(List.of(), 0, 1)));
    var second = result(save(f, body(List.of(), 0, 2)));
    assertThat(second.path("version").asLong()).isEqualTo(3);
    assertThat(Instant.parse(second.path("updatedAt").asText()))
        .isAfter(Instant.parse(first.path("updatedAt").asText()));
    var before = row(f);
    var checks = checks(f);
    assertThat(code(save(f, body(List.of(), 0, 2)))).isEqualTo("RECORD409_3");
    assertThat(row(f)).isEqualTo(before);
    assertThat(checks(f)).isEqualTo(checks);
  }

  @Test
  void fullCompletionStaysInProgressAndDoesNotGrantGrowthOrChangeTreeStage() throws Exception {
    var f = fixture();
    var steps =
        jdbc.queryForList(
            "SELECT step_id FROM activity_record_steps WHERE record_id=? AND is_required ORDER BY step_number",
            Long.class,
            f.record());
    var saved = result(save(f, body(steps, 100, 1)));
    assertThat(saved.path("progressPercent").asInt()).isEqualTo(100);
    assertThat(saved.path("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(row(f).get("growth_counted")).isEqualTo(false);
    assertThat(row(f).get("finished_at")).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_growth_contributions WHERE user_hobby_id=?",
                Integer.class,
                f.tree()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT current_stage_number FROM user_hobbies WHERE user_hobby_id=?",
                Integer.class,
                f.tree()))
        .isZero();
  }

  @Test
  void rejectsDuplicateMissingAndNonPositiveSnapshotIdsWithoutWriting() throws Exception {
    var f = fixture();
    var before = row(f);
    var checks = checks(f);
    for (var ids :
        List.of(
            List.of(f.first(), f.first()), List.of(Long.MAX_VALUE), List.of(0L), List.of(-1L))) {
      assertThat(code(save(f, body(ids, 600, 1)))).isEqualTo("RECORD400_2");
      assertThat(row(f)).isEqualTo(before);
      assertThat(checks(f)).isEqualTo(checks);
    }
  }

  @Test
  void rejectsOutOfRangeTimeWithoutChangingChecksVersionOrTimestamp() throws Exception {
    var f = fixture();
    var before = row(f);
    var checks = checks(f);
    for (var time : List.of(-1, 86401)) {
      assertThat(code(save(f, body(List.of(f.first()), time, 1)))).isEqualTo("RECORD400_2");
      assertThat(row(f)).isEqualTo(before);
      assertThat(checks(f)).isEqualTo(checks);
    }
  }

  @Test
  void rejectsMissingMalformedAndCoercedJsonWithoutWriting() throws Exception {
    var f = fixture();
    var before = row(f);
    for (var request :
        List.of(
            "{}",
            "{broken",
            "{\"completedStepIds\":null,\"expectedVersion\":1}",
            "{\"completedStepIds\":[],\"expectedVersion\":0}",
            "{\"completedStepIds\":[],\"expectedVersion\":null}",
            "{\"completedStepIds\":[],\"expectedVersion\":1.1}",
            "{\"completedStepIds\":[],\"expectedVersion\":\"1\"}",
            "{\"completedStepIds\":[],\"expectedVersion\":9223372036854775808}",
            "{\"completedStepIds\":[null],\"expectedVersion\":1}",
            "{\"completedStepIds\":[true],\"expectedVersion\":1}",
            "{\"completedStepIds\":[\"1\"],\"expectedVersion\":1}",
            "{\"completedStepIds\":[1.1],\"expectedVersion\":1}",
            "{\"completedStepIds\":1,\"expectedVersion\":1}",
            "{\"completedStepIds\":[],\"expectedVersion\":1,\"elapsedSeconds\":1.1}",
            "{\"completedStepIds\":[],\"expectedVersion\":1,\"elapsedSeconds\":\"1\"}")) {
      var response = save(f, request);
      assertThat(response.statusCode()).as(request).isEqualTo(400);
      assertThat(code(response)).as(request).isEqualTo("COMMON400_1");
      assertThat(row(f)).isEqualTo(before);
    }
    for (var id : List.of("0", "-1", "word", "1.5", "9223372036854775808"))
      assertThat(call(f.token(), id, body(List.of(), 0, 1)).statusCode()).as(id).isEqualTo(400);
  }

  @Test
  void requiresAuthenticatedActiveOwnerAndDoesNotExposeForeignRecords() throws Exception {
    var f = fixture();
    var before = row(f);
    assertThat(call(null, Long.toString(f.record()), body(List.of(), 0, 1)).statusCode())
        .isEqualTo(401);
    assertThat(code(call(f.token(), Long.toString(Long.MAX_VALUE), body(List.of(), 0, 1))))
        .isEqualTo("RECORD404_1");
    socialId = "record28-other-" + UUID.randomUUID();
    when(verifier.verify(any(), anyString())).thenReturn(socialId);
    var other = result(login());
    long id = other.path("userId").asLong();
    createdUsers.add(id);
    assertThat(id).isNotEqualTo(f.owner());
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    assertThat(
            code(
                call(
                    other.path("accessToken").asText(),
                    Long.toString(f.record()),
                    body(List.of(), 0, 1))))
        .isEqualTo("RECORD404_1");
    jdbc.update("UPDATE users SET account_status='PENDING_ONBOARDING' WHERE user_id=?", f.owner());
    assertThat(code(save(f, body(List.of(), 0, 1)))).isEqualTo("AUTH403_2");
    jdbc.update("UPDATE users SET account_status='SUSPENDED' WHERE user_id=?", f.owner());
    assertThat(code(save(f, body(List.of(), 0, 1)))).isEqualTo("AUTH403_1");
    jdbc.update(
        "UPDATE users SET account_status='WITHDRAWN',status_before_withdrawal='ACTIVE',withdrawn_at=now(),restorable_until=now()+interval '720 hours' WHERE user_id=?",
        f.owner());
    assertThat(code(save(f, body(List.of(), 0, 1)))).isEqualTo("AUTH403_3");
    assertThat(row(f)).isEqualTo(before);
  }

  @Test
  void rejectsInactiveTreesAndAllTerminalRecordStatesWithoutWriting() throws Exception {
    var f = fixture();
    for (var state : List.of("PAUSED", "ENDED")) {
      jdbc.update("UPDATE user_hobbies SET tree_status=? WHERE user_hobby_id=?", state, f.tree());
      var before = row(f);
      assertThat(code(save(f, body(List.of(), 0, 1)))).isEqualTo("TREE409_4");
      assertThat(row(f)).isEqualTo(before);
    }
    jdbc.update("UPDATE user_hobbies SET tree_status='ACTIVE' WHERE user_hobby_id=?", f.tree());
    for (var state : List.of("COMPLETED", "PARTIAL", "NOT_DONE")) {
      jdbc.update(
          "UPDATE activity_records SET activity_status=?,finished_at=started_at WHERE activity_record_id=?",
          state,
          f.record());
      var before = row(f);
      assertThat(code(save(f, body(List.of(), 0, 1)))).isEqualTo("RECORD409_2");
      assertThat(row(f)).isEqualTo(before);
    }
  }

  @Test
  void preservesUnknownLegacyProgressAndReturnsApproved409() throws Exception {
    var f = fixture();
    jdbc.update(
        "UPDATE activity_records SET activity_status='PARTIAL',finished_at=started_at WHERE activity_record_id=?",
        f.record());
    long legacy =
        jdbc.queryForObject(
            "INSERT INTO activity_records(user_hobby_id,activity_id,step_progress,attempt_count) VALUES(?,?,?::jsonb,4) RETURNING activity_record_id",
            Long.class,
            f.tree(),
            f.activity(),
            "{\"unproven\":true}");
    var before =
        jdbc.queryForMap("SELECT * FROM activity_records WHERE activity_record_id=?", legacy);
    assertThat(code(call(f.token(), Long.toString(legacy), body(List.of(), 0, 1))))
        .isEqualTo("RECORD409_5");
    assertThat(
            jdbc.queryForMap("SELECT * FROM activity_records WHERE activity_record_id=?", legacy))
        .isEqualTo(before);
  }

  @Test
  void usesOnlyStartMembershipAfterLiveCatalogueChangesAndKeepsSummaryTitle() throws Exception {
    var f = fixture();
    long newStep = step(f.activity(), 20, true);
    jdbc.update(
        "UPDATE activities SET activity_name='changed',review_status='DRAFT' WHERE activity_id=?",
        f.activity());
    jdbc.update("DELETE FROM activity_steps WHERE activity_id=?", f.activity());
    assertThat(code(save(f, body(List.of(newStep), 0, 1)))).isEqualTo("RECORD400_2");
    assertThat(save(f, body(List.of(f.first()), 55, 1)).statusCode()).isEqualTo(200);
    var tree = result(get(f, "/api/v1/user-hobbies/" + f.tree()));
    assertThat(tree.path("currentActivityRecord").path("title").asText())
        .isEqualTo("TEST ONLY record28 original");
    assertThat(tree.path("currentActivityRecord").path("progressPercent").asInt()).isEqualTo(33);
    var resumed =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/user-hobbies/"
                            + f.tree()
                            + "/activity-records"))
                .header("Authorization", "Bearer " + f.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"activityId\":" + f.activity() + "}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(result(resumed).path("version").asLong()).isEqualTo(2);
    assertThat(result(resumed).path("completedStepIds").get(0).asLong()).isEqualTo(f.first());
  }

  @Test
  void concurrentSameVersionRequestsHaveExactlyOneWinnerAndNoMixedChecks() throws Exception {
    var f = fixture();
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(
              () -> {
                barrier.await();
                return save(f, body(List.of(f.first()), 100, 1));
              });
      var b =
          executor.submit(
              () -> {
                barrier.await();
                return save(f, body(List.of(f.optional()), 200, 1));
              });
      var ra = a.get(15, TimeUnit.SECONDS);
      var rb = b.get(15, TimeUnit.SECONDS);
      assertThat(List.of(ra.statusCode(), rb.statusCode())).containsExactlyInAnyOrder(200, 409);
      var winner = result(ra.statusCode() == 200 ? ra : rb);
      assertThat(code(ra.statusCode() == 409 ? ra : rb)).isEqualTo("RECORD409_3");
      var detail = result(get(f, "/api/v1/activity-records/" + f.record()));
      assertThat(detail.path("completedStepIds")).isEqualTo(winner.path("completedStepIds"));
      assertThat(detail.path("elapsedSeconds")).isEqualTo(winner.path("elapsedSeconds"));
      assertThat(detail.path("version").asLong()).isEqualTo(2);
    }
  }

  @Test
  void failureAfterVersionAndStepWritesRollsBackEverythingAndCanRetry() throws Exception {
    var f = fixture();
    assertThat(save(f, body(List.of(f.first()), 120, 1)).statusCode()).isEqualTo(200);
    var before = row(f);
    var checks = checks(f);
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("TEST ONLY after step writes");
            })
        .when(progressRecords)
        .replaceCompleted(anyLong(), anyList());
    assertThat(save(f, body(List.of(f.optional()), 600, 2)).statusCode()).isEqualTo(500);
    assertThat(row(f)).isEqualTo(before);
    assertThat(checks(f)).isEqualTo(checks);
    doCallRealMethod().when(progressRecords).replaceCompleted(anyLong(), anyList());
    assertThat(result(save(f, body(List.of(f.optional()), 600, 2))).path("version").asLong())
        .isEqualTo(3);
  }

  @Test
  void rechecksVersionAfterWaitingForARecordWriter() throws Exception {
    var f = fixture();
    try (var writer = dataSource.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      writer.setAutoCommit(false);
      try (var statement =
          writer.prepareStatement(
              "UPDATE activity_records SET record_version=2,elapsed_seconds=333 WHERE activity_record_id=?")) {
        statement.setLong(1, f.record());
        statement.executeUpdate();
      }
      var request = executor.submit(() -> save(f, body(List.of(f.first()), 999, 1)));
      try {
        awaitLock("SELECT activity_status%");
        writer.commit();
        assertThat(code(request.get(10, TimeUnit.SECONDS))).isEqualTo("RECORD409_3");
        assertThat(row(f).get("record_version")).isEqualTo(2L);
        assertThat(row(f).get("elapsed_seconds")).isEqualTo(333);
        assertThat(
                result(get(f, "/api/v1/activity-records/" + f.record()))
                    .path("completedStepIds")
                    .size())
            .isZero();
      } finally {
        writer.rollback();
      }
    }
  }

  @Test
  void rechecksTreeStateAfterWaitingForAPauseWriter() throws Exception {
    var f = fixture();
    var before = row(f);
    try (var writer = dataSource.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      writer.setAutoCommit(false);
      try (var statement =
          writer.prepareStatement(
              "UPDATE user_hobbies SET tree_status='PAUSED' WHERE user_hobby_id=?")) {
        statement.setLong(1, f.tree());
        statement.executeUpdate();
      }
      var request = executor.submit(() -> save(f, body(List.of(f.first()), 999, 1)));
      try {
        awaitLock("%user_hobbies%for%update%");
        writer.commit();
        assertThat(code(request.get(10, TimeUnit.SECONDS))).isEqualTo("TREE409_4");
        assertThat(row(f)).isEqualTo(before);
      } finally {
        writer.rollback();
      }
    }
  }

  @Test
  void databaseTimestampAdvancesEvenIfPreviousTimestampIsAheadOfWallClock() throws Exception {
    var f = fixture();
    jdbc.update(
        "UPDATE activity_records SET updated_at=clock_timestamp()+interval '1 day' WHERE activity_record_id=?",
        f.record());
    Instant before = ((java.sql.Timestamp) row(f).get("updated_at")).toInstant();
    Instant after =
        Instant.parse(result(save(f, body(List.of(), 0, 1))).path("updatedAt").asText());
    assertThat(after).isEqualTo(before.plusNanos(1000));
  }

  private void awaitLock(String query) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    while (System.nanoTime() < end) {
      if (jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND wait_event_type='Lock' AND query ILIKE ?",
              Integer.class,
              query)
          > 0) return;
      Thread.sleep(10);
    }
    throw new AssertionError("Expected independent database lock wait");
  }

  private java.util.Map<String, Object> row(Fixture f) {
    return jdbc.queryForMap(
        "SELECT * FROM activity_records WHERE activity_record_id=?", f.record());
  }

  private List<java.util.Map<String, Object>> checks(Fixture f) {
    return jdbc.queryForList(
        "SELECT * FROM activity_record_steps WHERE record_id=? ORDER BY step_number", f.record());
  }

  private String body(List<Long> ids, int elapsed, long version) {
    return "{\"completedStepIds\":"
        + ids
        + ",\"elapsedSeconds\":"
        + elapsed
        + ",\"expectedVersion\":"
        + version
        + "}";
  }

  private HttpResponse<String> save(Fixture f, String body) throws Exception {
    return call(f.token(), Long.toString(f.record()), body);
  }

  private HttpResponse<String> call(String token, String id, String body) throws Exception {
    var builder =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/activity-records/" + id + "/progress"))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(Fixture f, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + f.token())
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String code(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("code").asText();
  }

  private Fixture fixture() throws Exception {
    var auth = result(login());
    long owner = auth.path("userId").asLong();
    createdUsers.add(owner);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", owner);
    long hobby =
        jdbc.queryForObject(
            "INSERT INTO hobbies(hobby_code,hobby_name,is_supported) VALUES(?,'TEST ONLY record28',true) RETURNING hobby_id",
            Long.class,
            "record28-test-" + UUID.randomUUID());
    long stage =
        jdbc.queryForObject(
            "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES(?,0,'TEST ONLY') RETURNING hobby_stage_id",
            Long.class,
            hobby);
    long activity =
        jdbc.queryForObject(
            "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status) VALUES(?,?,'TEST ONLY record28 original','PUBLISHED') RETURNING activity_id",
            Long.class,
            stage,
            "record28-test-" + UUID.randomUUID());
    long first = step(activity, 2, true);
    long optional = step(activity, 5, false);
    step(activity, 8, true);
    step(activity, 11, true);
    long tree =
        jdbc.queryForObject(
            "INSERT INTO user_hobbies(user_id,hobby_id) VALUES(?,?) RETURNING user_hobby_id",
            Long.class,
            owner,
            hobby);
    var response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/user-hobbies/"
                            + tree
                            + "/activity-records"))
                .header("Authorization", "Bearer " + auth.path("accessToken").asText())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"activityId\":" + activity + "}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(201);
    return new Fixture(
        owner,
        auth.path("accessToken").asText(),
        hobby,
        activity,
        tree,
        result(response).path("recordId").asLong(),
        first,
        optional);
  }

  private long step(long activity, int number, boolean required) {
    return jdbc.queryForObject(
        "INSERT INTO activity_steps(activity_id,step_number,title,instruction,is_required) VALUES(?,?,'TEST ONLY step','TEST ONLY instruction',?) RETURNING activity_step_id",
        Long.class,
        activity,
        number,
        required);
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update(
        "DELETE FROM user_hobbies WHERE hobby_id IN(SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'record28-test-%')");
    jdbc.update(
        "DELETE FROM activities WHERE hobby_stage_id IN(SELECT hs.hobby_stage_id FROM hobby_stages hs JOIN hobbies h ON h.hobby_id=hs.hobby_id WHERE h.hobby_code LIKE 'record28-test-%')");
    jdbc.update(
        "DELETE FROM hobby_stages WHERE hobby_id IN(SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'record28-test-%')");
    jdbc.update("DELETE FROM hobbies WHERE hobby_code LIKE 'record28-test-%'");
    super.cleanupSeededHobbies(userId);
  }

  private record Fixture(
      long owner,
      String token,
      long hobby,
      long activity,
      long tree,
      long record,
      long first,
      long optional) {}
}
