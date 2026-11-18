package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordSnapshotRepository;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
class ActivityRecordStartIntegrationTests extends IntegrationTestSupport {
  @MockitoSpyBean private ActivityRecordSnapshotRepository snapshots;

  @Test
  void capturesRealContentAndRequiredStepsAndReusesWithoutResettingAfterCatalogChanges()
      throws Exception {
    var f = fixture();
    var started = start(f.token(), f.tree(), f.activity());
    assertThat(started.statusCode()).isEqualTo(201);
    assertThat(code(started)).isEqualTo("RECORD201_1");
    var before = result(started);
    long record = before.path("recordId").asLong();
    assertThat(before.path("version").asLong()).isEqualTo(1);
    assertThat(before.path("progressPercent").asInt()).isZero();
    assertThat(before.path("completedStepIds").size()).isZero();
    assertThat(before.path("reused").asBoolean()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT content_snapshot->>'title' FROM activity_record_contents WHERE record_id=?",
                String.class,
                record))
        .isEqualTo("TEST ONLY original");
    assertThat(
            jdbc.queryForObject(
                "SELECT jsonb_exists(content_snapshot,'isSaved') OR jsonb_exists(content_snapshot,'steps') FROM activity_record_contents WHERE record_id=?",
                Boolean.class,
                record))
        .isFalse();
    jdbc.update(
        "UPDATE activity_steps SET title='new catalog',is_required=false WHERE activity_step_id=?",
        f.requiredStep());
    jdbc.update("DELETE FROM activity_steps WHERE activity_step_id=?", f.optionalStep());
    jdbc.update(
        "UPDATE activities SET activity_name='new catalog',review_status='DRAFT' WHERE activity_id=?",
        f.activity());
    jdbc.update(
        "UPDATE activity_record_steps SET is_completed=true WHERE record_id=? AND step_id=?",
        record,
        f.requiredStep());
    jdbc.update(
        "UPDATE activity_records SET record_version=2,elapsed_seconds=120 WHERE activity_record_id=?",
        record);
    var resumed = start(f.token(), f.tree(), f.activity());
    assertThat(resumed.statusCode()).isEqualTo(200);
    assertThat(code(resumed)).isEqualTo("RECORD200_1");
    var after = result(resumed);
    assertThat(after.path("recordId")).isEqualTo(before.path("recordId"));
    assertThat(after.path("startedAt")).isEqualTo(before.path("startedAt"));
    assertThat(after.path("contentVersion")).isEqualTo(before.path("contentVersion"));
    assertThat(after.path("version").asLong()).isEqualTo(2);
    assertThat(after.path("progressPercent").asInt()).isEqualTo(100);
    assertThat(after.path("completedStepIds").get(0).asLong()).isEqualTo(f.requiredStep());
    assertThat(after.path("reused").asBoolean()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_record_steps WHERE record_id=?",
                Integer.class,
                record))
        .isEqualTo(2);
  }

  @Test
  void optionalCompletionDoesNotIncreaseRequiredProgress() throws Exception {
    var f = fixture();
    long record = result(start(f.token(), f.tree(), f.activity())).path("recordId").asLong();
    jdbc.update(
        "UPDATE activity_record_steps SET is_completed=true WHERE record_id=? AND step_id=?",
        record,
        f.optionalStep());
    var resumed = result(start(f.token(), f.tree(), f.activity()));
    assertThat(resumed.path("progressPercent").asInt()).isZero();
    assertThat(resumed.path("completedStepIds").get(0).asLong()).isEqualTo(f.optionalStep());
  }

  @Test
  void requiresAuthenticationAndActiveOnboardedOwnership() throws Exception {
    var f = fixture();
    assertThat(start(null, f.tree(), f.activity()).statusCode()).isEqualTo(401);
    assertThat(code(start(f.token(), Long.MAX_VALUE, f.activity()))).isEqualTo("TREE404_1");
    long other =
        jdbc.queryForObject(
            "INSERT INTO users(provider,provider_user_id,account_status) VALUES('KAKAO',?,'ACTIVE') RETURNING user_id",
            Long.class,
            "record26-other-" + UUID.randomUUID());
    createdUsers.add(other);
    long otherTree =
        jdbc.queryForObject(
            "INSERT INTO user_hobbies(user_id,hobby_id) VALUES(?,?) RETURNING user_hobby_id",
            Long.class,
            other,
            f.hobby());
    assertThat(code(start(f.token(), otherTree, f.activity()))).isEqualTo("TREE404_1");
    jdbc.update("UPDATE users SET account_status='PENDING_ONBOARDING' WHERE user_id=?", f.owner());
    assertThat(code(start(f.token(), f.tree(), f.activity()))).isEqualTo("AUTH403_2");
    assertNoRecords(f.tree());
  }

  @Test
  void rejectsMalformedBodiesAndInvalidIds() throws Exception {
    var f = fixture();
    for (var body :
        List.of(
            "{}",
            "{\"activityId\":null}",
            "{\"activityId\":0}",
            "{\"activityId\":-1}",
            "{\"activityId\":true}",
            "{broken")) {
      assertThat(call(f.token(), f.tree(), body).statusCode()).as(body).isEqualTo(400);
    }
    assertThat(start(f.token(), 0, f.activity()).statusCode()).isEqualTo(400);
    assertNoRecords(f.tree());
  }

  @Test
  void rejectsPausedAndEndedTreesEvenWhenResuming() throws Exception {
    var f = fixture();
    var before = result(start(f.token(), f.tree(), f.activity()));
    for (var status : List.of("PAUSED", "ENDED")) {
      jdbc.update("UPDATE user_hobbies SET tree_status=? WHERE user_hobby_id=?", status, f.tree());
      assertThat(code(start(f.token(), f.tree(), f.activity()))).isEqualTo("TREE409_4");
      assertThat(
              jdbc.queryForObject(
                  "SELECT record_version FROM activity_records WHERE activity_record_id=?",
                  Long.class,
                  before.path("recordId").asLong()))
          .isEqualTo(1L);
    }
  }

  @Test
  void rejectsOtherInProgressActivityAndKeepsOriginalSnapshot() throws Exception {
    var f = fixture();
    var before = result(start(f.token(), f.tree(), f.activity()));
    long other = activity(f.stage());
    assertThat(code(start(f.token(), f.tree(), other))).isEqualTo("RECORD409_1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_records WHERE user_hobby_id=?",
                Integer.class,
                f.tree()))
        .isEqualTo(1);
    assertThat(result(start(f.token(), f.tree(), f.activity())).path("recordId"))
        .isEqualTo(before.path("recordId"));
  }

  @Test
  void rejectsPrivateUnsupportedMissingForeignHobbyAndLockedActivities() throws Exception {
    var f = fixture();
    assertThat(code(start(f.token(), f.tree(), Long.MAX_VALUE))).isEqualTo("ACTIVITY404_1");
    jdbc.update("UPDATE activities SET review_status='DRAFT' WHERE activity_id=?", f.activity());
    assertThat(code(start(f.token(), f.tree(), f.activity()))).isEqualTo("ACTIVITY404_1");
    jdbc.update(
        "UPDATE activities SET review_status='PUBLISHED' WHERE activity_id=?", f.activity());
    jdbc.update("UPDATE hobbies SET is_supported=false WHERE hobby_id=?", f.hobby());
    assertThat(code(start(f.token(), f.tree(), f.activity()))).isEqualTo("ACTIVITY404_1");
    jdbc.update("UPDATE hobbies SET is_supported=true WHERE hobby_id=?", f.hobby());
    long nextStage = stage(f.hobby(), 1);
    assertThat(code(start(f.token(), f.tree(), activity(nextStage)))).isEqualTo("ACTIVITY403_1");
    assertThat(code(start(f.token(), f.tree(), activity(stage(hobby(), 0)))))
        .isEqualTo("RECORD400_1");
    assertNoRecords(f.tree());
  }

  @Test
  void rejectsUncuratedEmptyAndZeroRequiredContentWithoutFabricatingARecord() throws Exception {
    var f = fixture();
    for (var update :
        List.of("title=NULL", "title=E'\\t'", "is_required=NULL", "is_required=false")) {
      jdbc.update(
          "UPDATE activity_steps SET title='TEST ONLY step',is_required=true WHERE activity_step_id=?",
          f.requiredStep());
      jdbc.update(
          "UPDATE activity_steps SET " + update + " WHERE activity_step_id=?", f.requiredStep());
      assertThat(code(start(f.token(), f.tree(), f.activity())))
          .as(update)
          .isEqualTo("ACTIVITY404_1");
    }
    jdbc.update("DELETE FROM activity_steps WHERE activity_id=?", f.activity());
    assertThat(code(start(f.token(), f.tree(), f.activity()))).isEqualTo("ACTIVITY404_1");
    assertNoRecords(f.tree());
  }

  @Test
  void preservesUnsupportedLegacyProgressAndReturnsTheApproved409WithoutInventingSnapshot()
      throws Exception {
    var f = fixture();
    long record =
        jdbc.queryForObject(
            "INSERT INTO activity_records(user_hobby_id,activity_id,step_progress,attempt_count) VALUES(?,?,?::jsonb,3) RETURNING activity_record_id",
            Long.class,
            f.tree(),
            f.activity(),
            "{\"unknownLegacy\":true}");
    var before =
        jdbc.queryForMap("SELECT * FROM activity_records WHERE activity_record_id=?", record);
    assertThat(code(start(f.token(), f.tree(), f.activity()))).isEqualTo("RECORD409_5");
    assertThat(
            jdbc.queryForMap("SELECT * FROM activity_records WHERE activity_record_id=?", record))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_record_contents WHERE record_id=?",
                Integer.class,
                record))
        .isZero();
  }

  @Test
  void canRestartAnAlreadyCreditedActivityWithoutGrantingGrowthAgain() throws Exception {
    var f = fixture();
    long previous =
        jdbc.queryForObject(
            "INSERT INTO activity_records(user_hobby_id,activity_id,activity_status,growth_counted,growth_stage_number) VALUES(?,?,'COMPLETED',true,0) RETURNING activity_record_id",
            Long.class,
            f.tree(),
            f.activity());
    jdbc.update(
        "INSERT INTO activity_growth_contributions(user_hobby_id,activity_id,origin_record_id,credited_stage_number,source,migrated_at) VALUES(?,?,?,0,'LEGACY_RECORD',now())",
        f.tree(),
        f.activity(),
        previous);
    var result = result(start(f.token(), f.tree(), f.activity()));
    assertThat(result.path("recordId").asLong()).isNotEqualTo(previous);
    assertThat(
            jdbc.queryForObject(
                "SELECT growth_counted FROM activity_records WHERE activity_record_id=?",
                Boolean.class,
                result.path("recordId").asLong()))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_growth_contributions WHERE user_hobby_id=?",
                Integer.class,
                f.tree()))
        .isEqualTo(1);
  }

  @Test
  void captureFailureRollsBackTheRecordAndEverySnapshotRow() throws Exception {
    var f = fixture();
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("test-only after capture");
            })
        .when(snapshots)
        .capture(anyLong(), any(), any(), anyString());
    assertThat(start(f.token(), f.tree(), f.activity()).statusCode()).isEqualTo(500);
    assertNoRecords(f.tree());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_record_contents c JOIN activity_records r ON r.activity_record_id=c.record_id WHERE r.user_hobby_id=?",
                Integer.class,
                f.tree()))
        .isZero();
  }

  @Test
  void snapshotMetadataAndMembershipAreImmutableButCompletedChecksCanChange() throws Exception {
    var f = fixture();
    long record = result(start(f.token(), f.tree(), f.activity())).path("recordId").asLong();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE activity_record_contents SET title='changed' WHERE record_id=?",
                    record))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE activity_record_steps SET is_required=false WHERE record_id=?", record))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM activity_record_steps WHERE record_id=?", record))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO activity_record_steps(record_id,step_id,step_number,title,description,is_required) VALUES(?,99999,99,'x','x',true)",
                    record))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    jdbc.update(
        "UPDATE activity_record_steps SET is_completed=true WHERE record_id=? AND step_id=?",
        record,
        f.requiredStep());
    assertThat(result(start(f.token(), f.tree(), f.activity())).path("progressPercent").asInt())
        .isEqualTo(100);
  }

  @Test
  void concurrentSameActivityStartsReturnOneNewRecordAndTheSameResumeIdentity() throws Exception {
    var f = fixture();
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(
              () -> {
                barrier.await();
                return start(f.token(), f.tree(), f.activity());
              });
      var b =
          executor.submit(
              () -> {
                barrier.await();
                return start(f.token(), f.tree(), f.activity());
              });
      var first = a.get(15, TimeUnit.SECONDS);
      var second = b.get(15, TimeUnit.SECONDS);
      assertThat(List.of(first.statusCode(), second.statusCode()))
          .containsExactlyInAnyOrder(201, 200);
      assertThat(result(first).path("recordId")).isEqualTo(result(second).path("recordId"));
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM activity_records WHERE user_hobby_id=?",
                  Integer.class,
                  f.tree()))
          .isEqualTo(1);
    }
  }

  @Test
  void concurrentDifferentActivitiesAllowOnlyOneInProgressRecord() throws Exception {
    var f = fixture();
    long other = activity(f.stage());
    step(other, 1, true);
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(
              () -> {
                barrier.await();
                return start(f.token(), f.tree(), f.activity());
              });
      var b =
          executor.submit(
              () -> {
                barrier.await();
                return start(f.token(), f.tree(), other);
              });
      assertThat(
              List.of(
                  a.get(15, TimeUnit.SECONDS).statusCode(),
                  b.get(15, TimeUnit.SECONDS).statusCode()))
          .containsExactlyInAnyOrder(201, 409);
    }
  }

  @Test
  void childEditorWaitsForStartAndCannotMixItsNewStepWithTheOldContentVersion() throws Exception {
    var f = fixture();
    var captured = new java.util.concurrent.CountDownLatch(1);
    var finish = new java.util.concurrent.CountDownLatch(1);
    doAnswer(
            invocation -> {
              captured.countDown();
              assertThat(finish.await(8, TimeUnit.SECONDS)).isTrue();
              return invocation.callRealMethod();
            })
        .when(snapshots)
        .capture(anyLong(), any(), any(), anyString());
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = executor.submit(() -> start(f.token(), f.tree(), f.activity()));
      try {
        assertThat(captured.await(5, TimeUnit.SECONDS)).isTrue();
        var edit =
            executor.submit(
                () -> {
                  jdbc.update(
                      "UPDATE activity_steps SET title='TEST ONLY edited' WHERE activity_step_id=?",
                      f.requiredStep());
                  return true;
                });
        awaitDatabaseLock("UPDATE activity_steps%");
        assertThat(edit.isDone()).isFalse();
        finish.countDown();
        var response = start.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(edit.get(5, TimeUnit.SECONDS)).isTrue();
        long record = result(response).path("recordId").asLong();
        assertThat(
                jdbc.queryForObject(
                    "SELECT title FROM activity_record_steps WHERE record_id=? AND step_id=?",
                    String.class,
                    record,
                    f.requiredStep()))
            .isEqualTo("TEST ONLY step");
        assertThat(
                jdbc.queryForObject(
                    "SELECT content_version FROM activities WHERE activity_id=?",
                    Long.class,
                    f.activity()))
            .isGreaterThan(result(response).path("contentVersion").asLong());
      } finally {
        finish.countDown();
      }
    }
  }

  @Test
  void startWaitingForAnEditorReadsTheCommittedNewVersionAndNewContentTogether() throws Exception {
    var f = fixture();
    try (var editor = dataSource.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      editor.setAutoCommit(false);
      try (var statement =
          editor.prepareStatement(
              "UPDATE activities SET activity_name='TEST ONLY edited' WHERE activity_id=?")) {
        statement.setLong(1, f.activity());
        statement.executeUpdate();
      }
      try (var statement =
          editor.prepareStatement(
              "UPDATE activity_steps SET title='TEST ONLY edited step' WHERE activity_step_id=?")) {
        statement.setLong(1, f.requiredStep());
        statement.executeUpdate();
      }
      var start = executor.submit(() -> start(f.token(), f.tree(), f.activity()));
      try {
        awaitDatabaseLock("SELECT activity_id FROM activities%");
        assertThat(start.isDone()).isFalse();
        editor.commit();
        var response = start.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(201);
        long record = result(response).path("recordId").asLong();
        assertThat(
                jdbc.queryForObject(
                    "SELECT title FROM activity_record_contents WHERE record_id=?",
                    String.class,
                    record))
            .isEqualTo("TEST ONLY edited");
        assertThat(
                jdbc.queryForObject(
                    "SELECT title FROM activity_record_steps WHERE record_id=? AND step_id=?",
                    String.class,
                    record,
                    f.requiredStep()))
            .isEqualTo("TEST ONLY edited step");
        assertThat(
                jdbc.queryForObject(
                    "SELECT content_version FROM activities WHERE activity_id=?",
                    Long.class,
                    f.activity()))
            .isEqualTo(result(response).path("contentVersion").asLong());
      } finally {
        editor.rollback();
      }
    }
  }

  private void awaitDatabaseLock(String queryPrefix) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      if (jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND wait_event_type='Lock' AND query LIKE ?",
              Integer.class,
              queryPrefix)
          > 0) return;
      Thread.sleep(25);
    }
    throw new AssertionError("Expected independent PostgreSQL lock wait");
  }

  private Fixture fixture() throws Exception {
    var auth = result(login());
    long owner = auth.path("userId").asLong();
    createdUsers.add(owner);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", owner);
    long hobby = hobby();
    long stage = stage(hobby, 0);
    long activity = activity(stage);
    long required = step(activity, 1, true);
    long optional = step(activity, 2, false);
    long tree =
        jdbc.queryForObject(
            "INSERT INTO user_hobbies(user_id,hobby_id) VALUES(?,?) RETURNING user_hobby_id",
            Long.class,
            owner,
            hobby);
    return new Fixture(
        owner, auth.path("accessToken").asText(), hobby, stage, activity, tree, required, optional);
  }

  private long hobby() {
    return jdbc.queryForObject(
        "INSERT INTO hobbies(hobby_code,hobby_name,is_supported) VALUES(?,'TEST ONLY record26',true) RETURNING hobby_id",
        Long.class,
        "record26-test-" + UUID.randomUUID());
  }

  private long stage(long hobby, int number) {
    return jdbc.queryForObject(
        "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES(?,?,'TEST ONLY') RETURNING hobby_stage_id",
        Long.class,
        hobby,
        number);
  }

  private long activity(long stage) {
    return jdbc.queryForObject(
        "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status) VALUES(?,?,'TEST ONLY original','PUBLISHED') RETURNING activity_id",
        Long.class,
        stage,
        "record26-test-" + UUID.randomUUID());
  }

  private long step(long activity, int number, boolean required) {
    return jdbc.queryForObject(
        "INSERT INTO activity_steps(activity_id,step_number,title,instruction,is_required) VALUES(?,?,'TEST ONLY step','TEST ONLY instruction',?) RETURNING activity_step_id",
        Long.class,
        activity,
        number,
        required);
  }

  private void assertNoRecords(long tree) {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_records WHERE user_hobby_id=?", Integer.class, tree))
        .isZero();
  }

  private HttpResponse<String> start(String token, long tree, long activity) throws Exception {
    return call(token, tree, "{\"activityId\":" + activity + "}");
  }

  private HttpResponse<String> call(String token, long tree, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/v1/user-hobbies/"
                        + tree
                        + "/activity-records"))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String code(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("code").asText();
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update(
        "DELETE FROM user_hobbies WHERE hobby_id IN(SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'record26-test-%')");
    jdbc.update(
        "DELETE FROM activities WHERE hobby_stage_id IN(SELECT hs.hobby_stage_id FROM hobby_stages hs JOIN hobbies h ON h.hobby_id=hs.hobby_id WHERE h.hobby_code LIKE 'record26-test-%')");
    jdbc.update(
        "DELETE FROM hobby_stages WHERE hobby_id IN(SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'record26-test-%')");
    jdbc.update("DELETE FROM hobbies WHERE hobby_code LIKE 'record26-test-%'");
    super.cleanupSeededHobbies(userId);
  }

  private record Fixture(
      long owner,
      String token,
      long hobby,
      long stage,
      long activity,
      long tree,
      long requiredStep,
      long optionalStep) {}
}
