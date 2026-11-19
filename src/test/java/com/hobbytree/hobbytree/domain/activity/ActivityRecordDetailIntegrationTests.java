package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordSnapshotRepository;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class ActivityRecordDetailIntegrationTests extends IntegrationTestSupport {
  @MockitoSpyBean private ActivityRecordSnapshotRepository snapshots;

  @Test
  void returnsStartSnapshotAndStoredMetadataWithoutWriting() throws Exception {
    var f = fixture();
    var before =
        jdbc.queryForMap("SELECT * FROM activity_records WHERE activity_record_id=?", f.record());
    var response = get(f.token(), Long.toString(f.record()));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(code(response)).isEqualTo("RECORD200_2");
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    var body = result(response);
    assertThat(body.path("recordId").asLong()).isEqualTo(f.record());
    assertThat(body.path("userHobbyId").asLong()).isEqualTo(f.tree());
    assertThat(body.path("activityId").asLong()).isEqualTo(f.activity());
    assertThat(body.path("title").asText()).isEqualTo("TEST ONLY record27 original");
    assertThat(body.path("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(body.path("treeStatus").asText()).isEqualTo("ACTIVE");
    assertThat(body.path("canUpdate").asBoolean()).isTrue();
    assertThat(body.path("elapsedSeconds").asInt()).isZero();
    assertThat(body.path("version").asLong()).isEqualTo(1);
    assertThat(body.path("contentVersion").asLong()).isPositive();
    assertThat(body.path("startedAt").isTextual()).isTrue();
    assertThat(body.path("finishedAt").isNull()).isTrue();
    assertThat(body.path("feedback").isNull()).isTrue();
    assertThat(body.path("steps").size()).isEqualTo(4);
    assertThat(body.path("steps").get(0).path("stepNumber").asInt()).isEqualTo(2);
    assertThat(body.path("steps").get(1).path("stepNumber").asInt()).isEqualTo(5);
    assertThat(body.path("steps").get(0).path("title").asText()).isEqualTo("TEST ONLY step");
    assertThat(body.path("steps").get(0).path("description").asText())
        .isEqualTo("TEST ONLY instruction");
    assertThat(body.path("steps").get(0).path("isRequired").asBoolean()).isTrue();
    assertThat(body.path("steps").get(0).path("imageUrl").asText())
        .isEqualTo("https://example.invalid/step.png");
    assertThat(body.path("completedStepIds").size()).isZero();
    assertThat(body.path("progressPercent").asInt()).isZero();
    assertThat(
            jdbc.queryForMap(
                "SELECT * FROM activity_records WHERE activity_record_id=?", f.record()))
        .isEqualTo(before);
  }

  @Test
  void usesRequiredSnapshotStepsForFloorProgressAndIncludesCheckedOptionalSteps() throws Exception {
    var f = fixture();
    jdbc.update(
        "UPDATE activity_record_steps SET is_completed=true WHERE record_id=? AND step_id IN (?,?)",
        f.record(),
        f.first(),
        f.optional());
    jdbc.update(
        "UPDATE activity_records SET elapsed_seconds=86400,record_version=8 WHERE activity_record_id=?",
        f.record());
    var body = result(get(f.token(), Long.toString(f.record())));
    assertThat(body.path("progressPercent").asInt()).isEqualTo(33);
    assertThat(body.path("completedStepIds").size()).isEqualTo(2);
    assertThat(body.path("completedStepIds").get(0).asLong()).isEqualTo(f.first());
    assertThat(body.path("completedStepIds").get(1).asLong()).isEqualTo(f.optional());
    assertThat(body.path("elapsedSeconds").asInt()).isEqualTo(86400);
    assertThat(body.path("version").asLong()).isEqualTo(8);
    jdbc.update(
        "UPDATE activity_record_steps SET is_completed=true WHERE record_id=? AND is_required",
        f.record());
    body = result(get(f.token(), Long.toString(f.record())));
    assertThat(body.path("progressPercent").asInt()).isEqualTo(100);
    assertThat(body.path("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(body.path("canUpdate").asBoolean()).isTrue();
  }

  @Test
  void catalogueEditsHiddenUnsupportedHobbyAndDeletedLiveStepsDoNotChangeHistory()
      throws Exception {
    var f = fixture();
    var before = result(get(f.token(), Long.toString(f.record())));
    jdbc.update(
        "UPDATE activities SET activity_name='changed',review_status='DRAFT' WHERE activity_id=?",
        f.activity());
    jdbc.update("UPDATE hobbies SET is_supported=false WHERE hobby_id=?", f.hobby());
    jdbc.update("DELETE FROM activity_steps WHERE activity_id=?", f.activity());
    assertThat(result(get(f.token(), Long.toString(f.record())))).isEqualTo(before);
  }

  @Test
  void pausedAndEndedTreesRemainReadableButCannotUpdate() throws Exception {
    var f = fixture();
    for (var status : List.of("PAUSED", "ENDED")) {
      jdbc.update("UPDATE user_hobbies SET tree_status=? WHERE user_hobby_id=?", status, f.tree());
      var response = get(f.token(), Long.toString(f.record()));
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(result(response).path("treeStatus").asText()).isEqualTo(status);
      assertThat(result(response).path("canUpdate").asBoolean()).isFalse();
    }
  }

  @Test
  void terminalRecordsReturnStoredFeedbackAndFinishedTimeWithoutChangingText() throws Exception {
    var f = fixture();
    for (var status : List.of("COMPLETED", "PARTIAL")) {
      jdbc.update(
          "UPDATE activity_records SET activity_status=?,finished_at=started_at+interval '10 minutes',satisfaction=4,perceived_difficulty='APPROPRIATE',feedback_memo=? WHERE activity_record_id=?",
          status,
          "  내 메모 🌱\n다음 활동  ",
          f.record());
      var body = result(get(f.token(), Long.toString(f.record())));
      assertThat(body.path("status").asText()).isEqualTo(status);
      assertThat(body.path("canUpdate").asBoolean()).isFalse();
      assertThat(body.path("feedback").path("satisfactionScore").asInt()).isEqualTo(4);
      assertThat(body.path("feedback").path("perceivedDifficulty").asText())
          .isEqualTo("APPROPRIATE");
      assertThat(body.path("feedback").path("memo").asText()).isEqualTo("  내 메모 🌱\n다음 활동  ");
      assertThat(java.time.Instant.parse(body.path("finishedAt").asText()))
          .isEqualTo(java.time.Instant.parse(body.path("startedAt").asText()).plusSeconds(600));
    }
  }

  @Test
  void absentFeedbackIsNullAndDoesNotInventScoresOrMemo() throws Exception {
    var f = fixture();
    jdbc.update(
        "UPDATE activity_records SET activity_status='NOT_DONE',finished_at=started_at WHERE activity_record_id=?",
        f.record());
    var body = result(get(f.token(), Long.toString(f.record())));
    assertThat(body.path("feedback").isNull()).isTrue();
    assertThat(body.path("canUpdate").asBoolean()).isFalse();
  }

  @Test
  void missingAndForeignRecordIdsReturnTheSame404WithoutLeakingFeedback() throws Exception {
    var f = fixture();
    assertThat(code(get(f.token(), Long.toString(Long.MAX_VALUE)))).isEqualTo("RECORD404_1");
    socialId = "record27-other-" + UUID.randomUUID();
    when(verifier.verify(any(), anyString())).thenReturn(socialId);
    var other = result(login());
    long otherId = other.path("userId").asLong();
    createdUsers.add(otherId);
    assertThat(otherId).isNotEqualTo(f.owner());
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", otherId);
    var response = get(other.path("accessToken").asText(), Long.toString(f.record()));
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(code(response)).isEqualTo("RECORD404_1");
    assertThat(mapper.readTree(response.body()).path("result").isNull()).isTrue();
  }

  @Test
  void requiresAuthenticationAndActiveOnboardedMembership() throws Exception {
    var f = fixture();
    assertThat(get(null, Long.toString(f.record())).statusCode()).isEqualTo(401);
    jdbc.update("UPDATE users SET account_status='PENDING_ONBOARDING' WHERE user_id=?", f.owner());
    assertThat(code(get(f.token(), Long.toString(f.record())))).isEqualTo("AUTH403_2");
    jdbc.update("UPDATE users SET account_status='SUSPENDED' WHERE user_id=?", f.owner());
    assertThat(code(get(f.token(), Long.toString(f.record())))).isEqualTo("AUTH403_1");
    jdbc.update(
        "UPDATE users SET account_status='WITHDRAWN',status_before_withdrawal='ACTIVE',withdrawn_at=now(),restorable_until=now()+interval '720 hours' WHERE user_id=?",
        f.owner());
    assertThat(code(get(f.token(), Long.toString(f.record())))).isEqualTo("AUTH403_3");
  }

  @Test
  void rejectsInvalidPathIds() throws Exception {
    var f = fixture();
    for (var value : List.of("0", "-1", "word", "1.5", "9223372036854775808")) {
      var response = get(f.token(), value);
      assertThat(response.statusCode()).as(value).isEqualTo(400);
      assertThat(code(response)).as(value).isEqualTo("COMMON400_1");
    }
  }

  @Test
  void preservesSnapshotlessRecordsInEveryStateAndReturnsApproved409InsteadOfFabricatedDetails()
      throws Exception {
    var f = fixture();
    for (var status : List.of("IN_PROGRESS", "COMPLETED", "PARTIAL", "NOT_DONE")) {
      jdbc.update(
          "UPDATE activity_records SET activity_status='PARTIAL',finished_at=started_at WHERE activity_record_id=?",
          f.record());
      long legacy =
          jdbc.queryForObject(
              "INSERT INTO activity_records(user_hobby_id,activity_id,activity_status,step_progress,attempt_count) VALUES(?,?,?,?::jsonb,3) RETURNING activity_record_id",
              Long.class,
              f.tree(),
              f.activity(),
              status,
              "{\"unknownLegacy\":true}");
      var before =
          jdbc.queryForMap("SELECT * FROM activity_records WHERE activity_record_id=?", legacy);
      var response = get(f.token(), Long.toString(legacy));
      assertThat(response.statusCode()).isEqualTo(409);
      assertThat(code(response)).isEqualTo("RECORD409_5");
      assertThat(
              jdbc.queryForMap("SELECT * FROM activity_records WHERE activity_record_id=?", legacy))
          .isEqualTo(before);
      jdbc.update("DELETE FROM activity_records WHERE activity_record_id=?", legacy);
    }
  }

  @Test
  void concurrentSaveCannotMixRecordVersionTreeStateAndCompletedSteps() throws Exception {
    var f = fixture();
    var reached = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              reached.countDown();
              assertThat(resume.await(8, TimeUnit.SECONDS)).isTrue();
              return invocation.callRealMethod();
            })
        .when(snapshots)
        .snapshot(anyLong());
    try (var executor = Executors.newSingleThreadExecutor()) {
      var response = executor.submit(() -> get(f.token(), Long.toString(f.record())));
      try {
        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue();
        try (var writer = dataSource.getConnection()) {
          writer.setAutoCommit(false);
          try (var stmt =
              writer.prepareStatement(
                  "UPDATE activity_records SET record_version=2,elapsed_seconds=600 WHERE activity_record_id=?")) {
            stmt.setLong(1, f.record());
            stmt.executeUpdate();
          }
          try (var stmt =
              writer.prepareStatement(
                  "UPDATE activity_record_steps SET is_completed=true WHERE record_id=? AND is_required")) {
            stmt.setLong(1, f.record());
            stmt.executeUpdate();
          }
          try (var stmt =
              writer.prepareStatement(
                  "UPDATE user_hobbies SET tree_status='PAUSED' WHERE user_hobby_id=?")) {
            stmt.setLong(1, f.tree());
            stmt.executeUpdate();
          }
          writer.commit();
        }
        resume.countDown();
        var body = result(response.get(10, TimeUnit.SECONDS));
        assertThat(body.path("version").asLong()).isEqualTo(1);
        assertThat(body.path("elapsedSeconds").asInt()).isZero();
        assertThat(body.path("progressPercent").asInt()).isZero();
        assertThat(body.path("treeStatus").asText()).isEqualTo("ACTIVE");
        assertThat(body.path("canUpdate").asBoolean()).isTrue();
        body = result(get(f.token(), Long.toString(f.record())));
        assertThat(body.path("version").asLong()).isEqualTo(2);
        assertThat(body.path("elapsedSeconds").asInt()).isEqualTo(600);
        assertThat(body.path("progressPercent").asInt()).isEqualTo(100);
        assertThat(body.path("canUpdate").asBoolean()).isFalse();
      } finally {
        resume.countDown();
      }
    }
  }

  private Fixture fixture() throws Exception {
    var auth = result(login());
    long owner = auth.path("userId").asLong();
    createdUsers.add(owner);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", owner);
    long hobby =
        jdbc.queryForObject(
            "INSERT INTO hobbies(hobby_code,hobby_name,is_supported) VALUES(?,'TEST ONLY record27',true) RETURNING hobby_id",
            Long.class,
            "record27-test-" + UUID.randomUUID());
    long stage =
        jdbc.queryForObject(
            "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES(?,0,'TEST ONLY') RETURNING hobby_stage_id",
            Long.class,
            hobby);
    long activity =
        jdbc.queryForObject(
            "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status) VALUES(?,?,'TEST ONLY record27 original','PUBLISHED') RETURNING activity_id",
            Long.class,
            stage,
            "record27-test-" + UUID.randomUUID());
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
    var start =
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
    assertThat(start.statusCode()).isEqualTo(201);
    return new Fixture(
        owner,
        auth.path("accessToken").asText(),
        hobby,
        activity,
        tree,
        result(start).path("recordId").asLong(),
        first,
        optional);
  }

  private long step(long activity, int number, boolean required) {
    return jdbc.queryForObject(
        "INSERT INTO activity_steps(activity_id,step_number,title,instruction,is_required,image_url) VALUES(?,?,'TEST ONLY step','TEST ONLY instruction',?,'https://example.invalid/step.png') RETURNING activity_step_id",
        Long.class,
        activity,
        number,
        required);
  }

  private HttpResponse<String> get(String token, String record) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/activity-records/" + record))
            .timeout(Duration.ofSeconds(12))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String code(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("code").asText();
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update(
        "DELETE FROM user_hobbies WHERE hobby_id IN(SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'record27-test-%')");
    jdbc.update(
        "DELETE FROM activities WHERE hobby_stage_id IN(SELECT hs.hobby_stage_id FROM hobby_stages hs JOIN hobbies h ON h.hobby_id=hs.hobby_id WHERE h.hobby_code LIKE 'record27-test-%')");
    jdbc.update(
        "DELETE FROM hobby_stages WHERE hobby_id IN(SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'record27-test-%')");
    jdbc.update("DELETE FROM hobbies WHERE hobby_code LIKE 'record27-test-%'");
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
