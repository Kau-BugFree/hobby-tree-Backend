package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.activity.repository.ActivityCompletionReceiptRepository;
import com.hobbytree.hobbytree.domain.experience.service.ExperienceAttendanceService;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.node.ObjectNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class ActivityRecordCompletionIntegrationTests extends IntegrationTestSupport {
  @MockitoSpyBean private ActivityCompletionReceiptRepository receipts;
  @Autowired private ExperienceAttendanceService attendances;

  @Test
  void completesWithFeedbackOneCreditAndStoredServerTime() throws Exception {
    var f = fixture(3);
    ready(f);
    var response = complete(f, body("COMPLETED", 2));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(code(response)).isEqualTo("RECORD200_4");
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    var value = result(response);
    assertThat(value.path("recordId").asLong()).isEqualTo(f.record());
    assertThat(value.path("userHobbyId").asLong()).isEqualTo(f.tree());
    assertThat(value.path("activityId").asLong()).isEqualTo(f.activity());
    assertThat(value.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(value.path("progressPercent").asInt()).isEqualTo(100);
    assertThat(value.path("version").asLong()).isEqualTo(3);
    assertThat(value.path("growth").path("growthApplied").asBoolean()).isTrue();
    assertThat(value.path("growth").path("distinctCompletedActivityCount").asLong()).isEqualTo(1);
    assertThat(value.path("growth").path("previousStageNumber").asInt()).isEqualTo(0);
    assertThat(value.path("growth").path("currentStageNumber").asInt()).isEqualTo(0);
    assertThat(value.path("feedback").path("satisfactionScore").asInt()).isEqualTo(4);
    var row = row(f);
    assertThat(row.get("growth_counted")).isEqualTo(true);
    assertThat(((Number) row.get("growth_stage_number")).intValue()).isZero();
    assertThat(Instant.parse(value.path("finishedAt").asText()))
        .isEqualTo(((java.sql.Timestamp) row.get("finished_at")).toInstant());
    assertThat(row.get("updated_at")).isEqualTo(row.get("finished_at"));
    assertThat(row.get("elapsed_seconds")).isEqualTo(120);
    assertThat(
            jdbc.queryForObject(
                "SELECT source FROM activity_growth_contributions WHERE origin_record_id=?",
                String.class,
                f.record()))
        .isEqualTo("SELF");
    var detail = result(get(f, "/api/v1/activity-records/" + f.record()));
    assertThat(detail.path("canUpdate").asBoolean()).isFalse();
    assertThat(detail.path("feedback")).isEqualTo(value.path("feedback"));
    assertThat(
            result(get(f, "/api/v1/user-hobbies/" + f.tree()))
                .path("currentActivityRecord")
                .isNull())
        .isTrue();
  }

  @Test
  void usesConfiguredThresholdAndKeepsBucketBasedTreeReadsConsistent() throws Exception {
    var f = fixture(2);
    seedCredits(f, 1);
    ready(f);
    var value = result(complete(f, body("COMPLETED", 2)));
    assertThat(value.path("growth").path("distinctCompletedActivityCount").asLong()).isEqualTo(2);
    assertThat(value.path("growth").path("currentStageNumber").asInt()).isEqualTo(1);
    assertThat(value.path("growth").path("stageChanged").asBoolean()).isTrue();
    var tree = result(get(f, "/api/v1/user-hobbies/" + f.tree()));
    assertThat(tree.path("currentStageNumber").asInt()).isEqualTo(1);
    assertThat(tree.path("currentStageCompletedCount").asLong()).isZero();
    assertThat(tree.path("remainingCountForNextStage").asLong()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT credited_stage_number FROM activity_growth_contributions WHERE origin_record_id=?",
                Integer.class,
                f.record()))
        .isZero();
  }

  @Test
  void maxStageStillAcceptsNewDistinctCreditWithoutAdvancingBeyondSix() throws Exception {
    var f = fixture(2);
    seedCredits(f, 10);
    ready(f);
    var growth = result(complete(f, body("COMPLETED", 2))).path("growth");
    assertThat(growth.path("growthApplied").asBoolean()).isTrue();
    assertThat(growth.path("distinctCompletedActivityCount").asLong()).isEqualTo(11);
    assertThat(growth.path("previousStageNumber").asInt()).isEqualTo(5);
    assertThat(growth.path("currentStageNumber").asInt()).isEqualTo(5);
    assertThat(growth.path("stageChanged").asBoolean()).isFalse();
    assertThat(growth.path("isMaxStage").asBoolean()).isTrue();
  }

  @Test
  void partialAndNotDonePersistFeedbackOrReasonWithoutGrowth() throws Exception {
    var f = fixture(3);
    assertThat(progress(f, List.of(f.first()), 1).statusCode()).isEqualTo(200);
    var partial = result(complete(f, body("PARTIAL", 2)));
    assertThat(partial.path("progressPercent").asInt()).isEqualTo(50);
    assertThat(partial.path("growth").path("growthApplied").asBoolean()).isFalse();
    var next = attempt(f, false);
    var request = body("NOT_DONE", 1);
    request.put("notDoneReason", "  시간이 부족해요 🌱  ");
    var notDone = result(complete(next, request));
    assertThat(notDone.path("progressPercent").asInt()).isZero();
    assertThat(notDone.path("feedback").isNull()).isTrue();
    assertThat(row(next).get("not_done_reason")).isEqualTo("  시간이 부족해요 🌱  ");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_growth_contributions WHERE user_hobby_id=?",
                Integer.class,
                f.tree()))
        .isZero();
  }

  @Test
  void repeatedActivityCompletionCreatesANewRecordButNeverASecondCredit() throws Exception {
    var f = fixture(3);
    ready(f);
    complete(f, body("COMPLETED", 2));
    var repeated = attempt(f, false);
    ready(repeated);
    var second = result(complete(repeated, body("COMPLETED", 2)));
    assertThat(repeated.record()).isNotEqualTo(f.record());
    assertThat(second.path("growth").path("growthApplied").asBoolean()).isFalse();
    assertThat(second.path("growth").path("distinctCompletedActivityCount").asLong()).isEqualTo(1);
    assertThat(row(repeated).get("growth_counted")).isEqualTo(false);
    assertThat(row(repeated).get("growth_stage_number")).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT origin_record_id FROM activity_growth_contributions WHERE user_hobby_id=? AND activity_id=?",
                Long.class,
                f.tree(),
                f.activity()))
        .isEqualTo(f.record());
  }

  @Test
  void identicalReplayIgnoresExpectedVersionAndFreezesOriginalGrowthAfterLaterCompletions()
      throws Exception {
    var f = fixture(3);
    ready(f);
    var first = complete(f, body("COMPLETED", 2));
    var second = attempt(f, true);
    ready(second);
    complete(second, body("COMPLETED", 2));
    var third = attempt(second, true);
    ready(third);
    complete(third, body("COMPLETED", 2));
    var before = state(f);
    var replay = complete(f, body("COMPLETED", 999));
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(replay.body()).isEqualTo(first.body());
    assertThat(state(f)).isEqualTo(before);
    assertThat(result(replay).path("growth").path("distinctCompletedActivityCount").asLong())
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT current_stage_number FROM user_hobbies WHERE user_hobby_id=?",
                Integer.class,
                f.tree()))
        .isEqualTo(1);
  }

  @Test
  void omittedAndNullOptionalValuesAreEquivalentAndStringsAreNotTrimmed() throws Exception {
    var f = fixture(3);
    ready(f);
    var request = body("COMPLETED", 2);
    ((ObjectNode) request.path("feedback")).remove("memo");
    var first = complete(f, request);
    var before = state(f);
    ((ObjectNode) request.path("feedback")).putNull("memo");
    request.putNull("notDoneReason");
    request.put("expectedVersion", 1);
    assertThat(complete(f, request).body()).isEqualTo(first.body());
    ((ObjectNode) request.path("feedback")).put("memo", "");
    assertThat(code(complete(f, request))).isEqualTo("RECORD409_2");
    assertThat(state(f)).isEqualTo(before);
  }

  @Test
  void validOptionalFeedbackAndReasonAreRetainedRegardlessOfStatus() throws Exception {
    var f = fixture(3);
    var request = body("PARTIAL", 1);
    request.put("status", "NOT_DONE").put("notDoneReason", "TEST ONLY reason");
    ((ObjectNode) request.path("feedback")).put("memo", "  메모 🌱\n  ");
    var first = complete(f, request);
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(row(f).get("feedback_memo")).isEqualTo("  메모 🌱\n  ");
    assertThat(
            result(get(f, "/api/v1/activity-records/" + f.record()))
                .path("feedback")
                .path("memo")
                .asText())
        .isEqualTo("  메모 🌱\n  ");
    request.put("notDoneReason", "TEST ONLY reason ");
    assertThat(code(complete(f, request))).isEqualTo("RECORD409_2");
  }

  @Test
  void rejectsDifferentStatusFeedbackAndReasonWithoutChangingCompletion() throws Exception {
    var f = fixture(3);
    ready(f);
    complete(f, body("COMPLETED", 2));
    var before = state(f);
    for (var request :
        List.of(
            body("PARTIAL", 2),
            body("NOT_DONE", 2),
            body("COMPLETED", 2).put("notDoneReason", "new reason"))) {
      assertThat(code(complete(f, request))).isEqualTo("RECORD409_2");
      assertThat(state(f)).isEqualTo(before);
    }
    var request = body("COMPLETED", 2);
    ((ObjectNode) request.path("feedback")).put("satisfactionScore", 3);
    assertThat(code(complete(f, request))).isEqualTo("RECORD409_2");
    assertThat(state(f)).isEqualTo(before);
  }

  @Test
  void requiresAllRequiredStepsAndCurrentVersionWithoutImplicitlyCheckingSteps() throws Exception {
    var f = fixture(3);
    var before = state(f);
    assertThat(code(complete(f, body("COMPLETED", 1)))).isEqualTo("RECORD409_4");
    assertThat(state(f)).isEqualTo(before);
    ready(f);
    before = state(f);
    assertThat(code(complete(f, body("COMPLETED", 1)))).isEqualTo("RECORD409_3");
    assertThat(state(f)).isEqualTo(before);
    assertThat(complete(f, body("COMPLETED", 2)).statusCode()).isEqualTo(200);
  }

  @Test
  void rejectsSemanticFeedbackAndReasonErrorsWithoutAnyWrites() throws Exception {
    var f = fixture(3);
    var before = state(f);
    var invalid =
        List.of(
            mapper.createObjectNode().put("status", "OTHER").put("expectedVersion", 1),
            mapper.createObjectNode().put("status", "COMPLETED").put("expectedVersion", 1),
            mapper.createObjectNode().put("status", "NOT_DONE").put("expectedVersion", 1),
            body("NOT_DONE", 1).put("notDoneReason", " "),
            body("NOT_DONE", 1).put("notDoneReason", "x".repeat(201)),
            body("COMPLETED", 1));
    ((ObjectNode) invalid.getLast().path("feedback")).put("satisfactionScore", 0);
    for (var request : invalid) {
      assertThat(code(complete(f, request))).as(request.toString()).isEqualTo("RECORD400_3");
      assertThat(state(f)).isEqualTo(before);
    }
    for (var difficulty : List.of("easy", "UNKNOWN", "")) {
      var request = body("PARTIAL", 1);
      ((ObjectNode) request.path("feedback")).put("perceivedDifficulty", difficulty);
      assertThat(code(complete(f, request))).isEqualTo("RECORD400_3");
    }
    for (var score : List.of(-1, 6)) {
      var request = body("PARTIAL", 1);
      ((ObjectNode) request.path("feedback")).put("satisfactionScore", score);
      assertThat(code(complete(f, request))).isEqualTo("RECORD400_3");
    }
    assertThat(state(f)).isEqualTo(before);
  }

  @Test
  void countsUnicodeCodePointsAndRejectsNulAndBrokenSurrogatesAsDomainErrors() throws Exception {
    var f = fixture(3);
    var before = state(f);
    for (var memo : List.of("🌱".repeat(1001), "bad" + ((char) 0))) {
      var request = body("PARTIAL", 1);
      ((ObjectNode) request.path("feedback")).put("memo", memo);
      assertThat(code(complete(f, request))).isEqualTo("RECORD400_3");
      assertThat(state(f)).isEqualTo(before);
    }
    var broken =
        "{\"status\":\"PARTIAL\",\"expectedVersion\":1,\"feedback\":{\"satisfactionScore\":4,\"perceivedDifficulty\":\"EASY\",\"memo\":\""
            + "\\"
            + "uD800\"}}";
    assertThat(code(call(f.token(), Long.toString(f.record()), broken))).isEqualTo("RECORD400_3");
    assertThat(state(f)).isEqualTo(before);
    var valid = body("PARTIAL", 1);
    ((ObjectNode) valid.path("feedback")).put("memo", "🌱".repeat(1000));
    assertThat(complete(f, valid).statusCode()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT char_length(feedback_memo) FROM activity_records WHERE activity_record_id=?",
                Integer.class,
                f.record()))
        .isEqualTo(1000);
  }

  @Test
  void strictTypesAndInvalidVersionProduceCommon400InsteadOfCoercion() throws Exception {
    var f = fixture(3);
    var before = state(f);
    for (var text :
        List.of(
            "{broken",
            "{}",
            "{\"status\":4,\"expectedVersion\":1}",
            "{\"status\":\"PARTIAL\",\"expectedVersion\":0}",
            "{\"status\":\"PARTIAL\",\"expectedVersion\":1.1}",
            "{\"status\":\"PARTIAL\",\"expectedVersion\":\"1\"}",
            "{\"status\":\"PARTIAL\",\"expectedVersion\":9223372036854775808}",
            "{\"status\":\"PARTIAL\",\"expectedVersion\":1,\"feedback\":{\"satisfactionScore\":4.9}}",
            "{\"status\":\"PARTIAL\",\"expectedVersion\":1,\"feedback\":{\"satisfactionScore\":\"4\"}}",
            "{\"status\":\"PARTIAL\",\"expectedVersion\":1,\"feedback\":{\"memo\":false}}")) {
      var response = call(f.token(), Long.toString(f.record()), text);
      assertThat(code(response)).as(text).isEqualTo("COMMON400_1");
      assertThat(state(f)).isEqualTo(before);
    }
    for (var id : List.of("0", "-1", "word", "1.5", "9223372036854775808"))
      assertThat(call(f.token(), id, body("PARTIAL", 1).toString()).statusCode()).isEqualTo(400);
  }

  @Test
  void checksActiveMembershipOwnershipAndTreeStateEvenOnReplay() throws Exception {
    var f = fixture(3);
    ready(f);
    assertThat(call(null, Long.toString(f.record()), body("COMPLETED", 2).toString()).statusCode())
        .isEqualTo(401);
    assertThat(
            code(call(f.token(), Long.toString(Long.MAX_VALUE), body("COMPLETED", 2).toString())))
        .isEqualTo("RECORD404_1");
    socialId = "record29-other-" + UUID.randomUUID();
    when(verifier.verify(any(), anyString())).thenReturn(socialId);
    var other = result(login());
    long otherId = other.path("userId").asLong();
    createdUsers.add(otherId);
    assertThat(otherId).isNotEqualTo(f.owner());
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", otherId);
    assertThat(
            code(
                call(
                    other.path("accessToken").asText(),
                    Long.toString(f.record()),
                    body("COMPLETED", 2).toString())))
        .isEqualTo("RECORD404_1");
    for (var status : List.of("PAUSED", "ENDED")) {
      jdbc.update("UPDATE user_hobbies SET tree_status=? WHERE user_hobby_id=?", status, f.tree());
      assertThat(code(complete(f, body("COMPLETED", 2)))).isEqualTo("TREE409_4");
    }
    jdbc.update("UPDATE user_hobbies SET tree_status='ACTIVE' WHERE user_hobby_id=?", f.tree());
    assertThat(complete(f, body("COMPLETED", 2)).statusCode()).isEqualTo(200);
    jdbc.update("UPDATE user_hobbies SET tree_status='PAUSED' WHERE user_hobby_id=?", f.tree());
    assertThat(code(complete(f, body("COMPLETED", 2)))).isEqualTo("TREE409_4");
    jdbc.update("UPDATE users SET account_status='PENDING_ONBOARDING' WHERE user_id=?", f.owner());
    assertThat(code(complete(f, body("COMPLETED", 2)))).isEqualTo("AUTH403_2");
    jdbc.update("UPDATE users SET account_status='SUSPENDED' WHERE user_id=?", f.owner());
    assertThat(code(complete(f, body("COMPLETED", 2)))).isEqualTo("AUTH403_1");
    jdbc.update(
        "UPDATE users SET account_status='WITHDRAWN',status_before_withdrawal='ACTIVE',withdrawn_at=now(),restorable_until=now()+interval '720 hours' WHERE user_id=?",
        f.owner());
    assertThat(code(complete(f, body("COMPLETED", 2)))).isEqualTo("AUTH403_3");
  }

  @Test
  void preservesLegacyProgressAndDoesNotInventAReceiptForPastTerminations() throws Exception {
    var f = fixture(3);
    complete(f, body("NOT_DONE", 1));
    for (var status : List.of("IN_PROGRESS", "COMPLETED", "PARTIAL", "NOT_DONE")) {
      long id =
          jdbc.queryForObject(
              "INSERT INTO activity_records(user_hobby_id,activity_id,activity_status,step_progress,attempt_count) VALUES(?,?,?,?::jsonb,4) RETURNING activity_record_id",
              Long.class,
              f.tree(),
              f.activity(),
              status,
              "{\"unproven\":true}");
      var before =
          jdbc.queryForMap("SELECT * FROM activity_records WHERE activity_record_id=?", id);
      assertThat(code(call(f.token(), Long.toString(id), body("PARTIAL", 1).toString())))
          .isEqualTo(status.equals("IN_PROGRESS") ? "RECORD409_5" : "RECORD409_2");
      assertThat(jdbc.queryForMap("SELECT * FROM activity_records WHERE activity_record_id=?", id))
          .isEqualTo(before);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM activity_completion_receipts WHERE record_id=?",
                  Integer.class,
                  id))
          .isZero();
      jdbc.update("DELETE FROM activity_records WHERE activity_record_id=?", id);
    }
  }

  @Test
  void catalogueChangesDoNotInvalidateTheStartedCompletionSnapshot() throws Exception {
    var f = fixture(3);
    ready(f);
    jdbc.update("DELETE FROM activity_steps WHERE activity_id=?", f.activity());
    jdbc.update("UPDATE activities SET review_status='DRAFT' WHERE activity_id=?", f.activity());
    jdbc.update("UPDATE hobbies SET is_supported=false WHERE hobby_id=?", f.hobby());
    assertThat(complete(f, body("COMPLETED", 2)).statusCode()).isEqualTo(200);
  }

  @Test
  void identicalConcurrentCompletionsReturnTheSameFirstResultAndCreditOnlyOnce() throws Exception {
    var f = fixture(3);
    ready(f);
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(
              () -> {
                barrier.await();
                return complete(f, body("COMPLETED", 2));
              });
      var b =
          executor.submit(
              () -> {
                barrier.await();
                return complete(f, body("COMPLETED", 2));
              });
      var first = a.get(15, TimeUnit.SECONDS);
      var second = b.get(15, TimeUnit.SECONDS);
      assertThat(first.statusCode()).isEqualTo(200);
      assertThat(second.body()).isEqualTo(first.body());
      assertThat(row(f).get("record_version")).isEqualTo(3L);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM activity_growth_contributions WHERE user_hobby_id=?",
                  Integer.class,
                  f.tree()))
          .isEqualTo(1);
    }
  }

  @Test
  void differentConcurrentEndingsHaveOneWinnerWithoutMixedFeedbackOrGrowth() throws Exception {
    var f = fixture(3);
    ready(f);
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(
              () -> {
                barrier.await();
                return complete(f, body("COMPLETED", 2));
              });
      var b =
          executor.submit(
              () -> {
                barrier.await();
                return complete(f, body("PARTIAL", 2));
              });
      var first = a.get(15, TimeUnit.SECONDS);
      var second = b.get(15, TimeUnit.SECONDS);
      assertThat(List.of(first.statusCode(), second.statusCode()))
          .containsExactlyInAnyOrder(200, 409);
      var winner = result(first.statusCode() == 200 ? first : second);
      assertThat(row(f).get("activity_status")).isEqualTo(winner.path("status").asText());
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM activity_growth_contributions WHERE user_hobby_id=?",
                  Long.class,
                  f.tree()))
          .isEqualTo(winner.path("growth").path("growthApplied").asBoolean() ? 1L : 0L);
    }
  }

  @Test
  void concurrentProgressAndCompletionNeverBothApplyTheSameVersion() throws Exception {
    var f = fixture(3);
    ready(f);
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(
              () -> {
                barrier.await();
                return complete(f, body("COMPLETED", 2));
              });
      var b =
          executor.submit(
              () -> {
                barrier.await();
                return progress(f, List.of(), 2);
              });
      var first = a.get(15, TimeUnit.SECONDS);
      var second = b.get(15, TimeUnit.SECONDS);
      assertThat(List.of(first.statusCode(), second.statusCode()))
          .containsExactlyInAnyOrder(200, 409);
      assertThat(row(f).get("record_version")).isEqualTo(3L);
      assertThat(first.statusCode() == 200 ? code(second) : code(first))
          .isIn("RECORD409_2", "RECORD409_3");
    }
  }

  @Test
  void selfAndExperienceCreditRacePreservesSelfSnapshotAndReservationWithOneSharedCredit()
      throws Exception {
    var f = fixture(3);
    ready(f);
    long reservation = reservation(f);
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(
              () -> {
                barrier.await();
                return complete(f, body("COMPLETED", 2));
              });
      var b =
          executor.submit(
              () -> {
                barrier.await();
                return attendances.applyAttendance(f.owner(), reservation, f.tree());
              });
      assertThat(a.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      b.get(15, TimeUnit.SECONDS);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM activity_growth_contributions WHERE user_hobby_id=?",
                  Integer.class,
                  f.tree()))
          .isEqualTo(1);
      assertThat(row(f).get("activity_status")).isEqualTo("COMPLETED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT reservation_status FROM experience_reservations WHERE reservation_id=?",
                  String.class,
                  reservation))
          .isEqualTo("ATTENDED");
    }
  }

  @Test
  void laterExperienceRecognitionKeepsSelfOriginAndFrozenCompletionReceipt() throws Exception {
    var f = fixture(3);
    ready(f);
    var first = complete(f, body("COMPLETED", 2));
    assertThat(first.statusCode()).isEqualTo(200);
    attendances.applyAttendance(f.owner(), reservation(f), f.tree());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_growth_contributions WHERE user_hobby_id=?",
                Integer.class,
                f.tree()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT source FROM activity_growth_contributions WHERE origin_record_id=?",
                String.class,
                f.record()))
        .isEqualTo("SELF");
    assertThat(complete(f, body("COMPLETED", 999)).body()).isEqualTo(first.body());
  }

  @Test
  void previouslyVerifiedExperienceCreditDoesNotCountAgainOnSelfCompletion() throws Exception {
    var f = fixture(3);
    long reservation = reservation(f);
    attendances.applyAttendance(f.owner(), reservation, f.tree());
    ready(f);
    var response = result(complete(f, body("COMPLETED", 2)));
    assertThat(response.path("growth").path("growthApplied").asBoolean()).isFalse();
    assertThat(response.path("growth").path("distinctCompletedActivityCount").asLong())
        .isEqualTo(1);
    long external =
        jdbc.queryForObject(
            "SELECT origin_record_id FROM activity_growth_contributions WHERE user_hobby_id=?",
            Long.class,
            f.tree());
    assertThat(external).isNotEqualTo(f.record());
    assertThat(code(call(f.token(), Long.toString(external), body("COMPLETED", 1).toString())))
        .isEqualTo("RECORD409_2");
  }

  @Test
  void receiptFailureRollsBackRecordFeedbackCreditAndStageAndAllowsRetry() throws Exception {
    var f = fixture(3);
    seedCredits(f, 2);
    ready(f);
    var before = state(f);
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("TEST ONLY after immutable receipt insert");
            })
        .when(receipts)
        .save(anyLong(), anyString(), anyString());
    assertThat(complete(f, body("COMPLETED", 2)).statusCode()).isEqualTo(500);
    assertThat(state(f)).isEqualTo(before);
    doCallRealMethod().when(receipts).save(anyLong(), anyString(), anyString());
    var retried = result(complete(f, body("COMPLETED", 2)));
    assertThat(retried.path("growth").path("currentStageNumber").asInt()).isEqualTo(1);
  }

  @Test
  void growthBaselineAndMirrorMismatchStopWithoutInventingCorrections() throws Exception {
    var f = fixture(3);
    ready(f);
    jdbc.update("UPDATE user_hobbies SET current_stage_number=1 WHERE user_hobby_id=?", f.tree());
    var before = state(f);
    assertThat(complete(f, body("COMPLETED", 2)).statusCode()).isEqualTo(500);
    assertThat(state(f)).isEqualTo(before);
    jdbc.update("UPDATE user_hobbies SET current_stage_number=0 WHERE user_hobby_id=?", f.tree());
    seedCredits(f, 1);
    jdbc.update("DELETE FROM activity_growth_contributions WHERE user_hobby_id=?", f.tree());
    before = state(f);
    assertThat(complete(f, body("COMPLETED", 2)).statusCode()).isEqualTo(500);
    assertThat(state(f)).isEqualTo(before);
  }

  @Test
  void receiptIsImmutableWhileExistingTreePurgeStillCascades() throws Exception {
    var f = fixture(3);
    ready(f);
    complete(f, body("COMPLETED", 2));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE activity_completion_receipts SET response_payload='{}'::jsonb WHERE record_id=?",
                    f.record()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM activity_completion_receipts WHERE record_id=?", f.record()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    jdbc.update("DELETE FROM user_hobbies WHERE user_hobby_id=?", f.tree());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_completion_receipts WHERE record_id=?",
                Integer.class,
                f.record()))
        .isZero();
  }

  @Test
  void completionTimeCannotPrecedeStoredStartOrLastMutation() throws Exception {
    var f = fixture(3);
    ready(f);
    jdbc.update(
        "UPDATE activity_records SET updated_at=clock_timestamp()+interval '1 day' WHERE activity_record_id=?",
        f.record());
    var before = ((java.sql.Timestamp) row(f).get("updated_at")).toInstant();
    assertThat(Instant.parse(result(complete(f, body("COMPLETED", 2))).path("finishedAt").asText()))
        .isEqualTo(before.plusNanos(1000));
  }

  private Map<String, Object> row(Fixture f) {
    return jdbc.queryForMap(
        "SELECT * FROM activity_records WHERE activity_record_id=?", f.record());
  }

  private Map<String, Object> state(Fixture f) {
    return Map.of(
        "record",
        row(f),
        "tree",
        jdbc.queryForMap("SELECT * FROM user_hobbies WHERE user_hobby_id=?", f.tree()),
        "steps",
        jdbc.queryForList(
            "SELECT * FROM activity_record_steps WHERE record_id=? ORDER BY step_number",
            f.record()),
        "growth",
        jdbc.queryForList(
            "SELECT * FROM activity_growth_contributions WHERE user_hobby_id=? ORDER BY contribution_id",
            f.tree()),
        "receipts",
        jdbc.queryForList(
            "SELECT * FROM activity_completion_receipts WHERE record_id=?", f.record()));
  }

  private ObjectNode body(String status, long version) {
    var value = mapper.createObjectNode().put("status", status).put("expectedVersion", version);
    if (status.equals("NOT_DONE")) value.put("notDoneReason", "TEST ONLY reason");
    else
      value.set(
          "feedback",
          mapper
              .createObjectNode()
              .put("satisfactionScore", 4)
              .put("perceivedDifficulty", "APPROPRIATE")
              .put("memo", "TEST ONLY memo"));
    return value;
  }

  private HttpResponse<String> complete(Fixture f, ObjectNode request) throws Exception {
    return call(f.token(), Long.toString(f.record()), mapper.writeValueAsString(request));
  }

  private HttpResponse<String> call(String token, String id, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/activity-records/" + id + "/complete"))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
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

  private HttpResponse<String> progress(Fixture f, List<Long> ids, long version) throws Exception {
    return client.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/v1/activity-records/"
                        + f.record()
                        + "/progress"))
            .timeout(Duration.ofSeconds(12))
            .header("Authorization", "Bearer " + f.token())
            .header("Content-Type", "application/json")
            .method(
                "PATCH",
                HttpRequest.BodyPublishers.ofString(
                    "{\"completedStepIds\":"
                        + ids
                        + ",\"elapsedSeconds\":120,\"expectedVersion\":"
                        + version
                        + "}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void ready(Fixture f) throws Exception {
    assertThat(progress(f, List.of(f.first(), f.second()), 1).statusCode()).isEqualTo(200);
  }

  private Fixture fixture(int k) throws Exception {
    var auth = result(login());
    long owner = auth.path("userId").asLong();
    createdUsers.add(owner);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", owner);
    long hobby =
        jdbc.queryForObject(
            "INSERT INTO hobbies(hobby_code,hobby_name,is_supported,activities_per_stage) VALUES(?,'TEST ONLY record29',true,?) RETURNING hobby_id",
            Long.class,
            "record29-test-" + UUID.randomUUID(),
            k);
    long stage =
        jdbc.queryForObject(
            "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES(?,0,'TEST ONLY') RETURNING hobby_stage_id",
            Long.class,
            hobby);
    long tree =
        jdbc.queryForObject(
            "INSERT INTO user_hobbies(user_id,hobby_id) VALUES(?,?) RETURNING user_hobby_id",
            Long.class,
            owner,
            hobby);
    var activity = activity(stage);
    return start(
        new Fixture(
            owner,
            auth.path("accessToken").asText(),
            hobby,
            stage,
            tree,
            0,
            activity.id(),
            activity.first(),
            activity.second(),
            k));
  }

  private Fixture start(Fixture f) throws Exception {
    var response =
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
    assertThat(response.statusCode()).isEqualTo(201);
    return new Fixture(
        f.owner(),
        f.token(),
        f.hobby(),
        f.stage(),
        f.tree(),
        result(response).path("recordId").asLong(),
        f.activity(),
        f.first(),
        f.second(),
        f.k());
  }

  private Fixture attempt(Fixture f, boolean newActivity) throws Exception {
    var a = newActivity ? activity(f.stage()) : new Activity(f.activity(), f.first(), f.second());
    return start(
        new Fixture(
            f.owner(),
            f.token(),
            f.hobby(),
            f.stage(),
            f.tree(),
            0,
            a.id(),
            a.first(),
            a.second(),
            f.k()));
  }

  private Activity activity(long stage) {
    long id =
        jdbc.queryForObject(
            "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status) VALUES(?,?,'TEST ONLY original','PUBLISHED') RETURNING activity_id",
            Long.class,
            stage,
            "record29-test-" + UUID.randomUUID());
    long first = step(id, 2, true);
    long second = step(id, 8, true);
    step(id, 5, false);
    return new Activity(id, first, second);
  }

  private long step(long activity, int number, boolean required) {
    return jdbc.queryForObject(
        "INSERT INTO activity_steps(activity_id,step_number,title,instruction,is_required) VALUES(?,?,'TEST ONLY step','TEST ONLY instruction',?) RETURNING activity_step_id",
        Long.class,
        activity,
        number,
        required);
  }

  private void seedCredits(Fixture f, int count) {
    for (int i = 0; i < count; i++) {
      var a = activity(f.stage());
      int at = Math.min(5, i / f.k());
      long record =
          jdbc.queryForObject(
              "INSERT INTO activity_records(user_hobby_id,activity_id,activity_status,growth_counted,growth_stage_number) VALUES(?,?,'COMPLETED',true,?) RETURNING activity_record_id",
              Long.class,
              f.tree(),
              a.id(),
              at);
      jdbc.update(
          "INSERT INTO activity_growth_contributions(user_hobby_id,activity_id,origin_record_id,credited_stage_number,source,migrated_at) VALUES(?,?,?,?,'LEGACY_RECORD',clock_timestamp())",
          f.tree(),
          a.id(),
          record,
          at);
    }
    jdbc.update(
        "UPDATE user_hobbies SET current_stage_number=? WHERE user_hobby_id=?",
        Math.min(5, count / f.k()),
        f.tree());
  }

  private long reservation(Fixture f) {
    long experience =
        jdbc.queryForObject(
            "INSERT INTO experiences(experience_type,title,listing_status,hobby_id,linked_activity_id) VALUES('CLASS',?,'OPEN',?,?) RETURNING experience_id",
            Long.class,
            "record29-test-" + UUID.randomUUID(),
            f.hobby(),
            f.activity());
    return jdbc.queryForObject(
        "INSERT INTO experience_reservations(reservation_code,experience_id,user_id,reservation_status) VALUES(?,?,?,'CONFIRMED') RETURNING reservation_id",
        Long.class,
        "record29-test-" + UUID.randomUUID(),
        experience,
        f.owner());
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update(
        "DELETE FROM experience_reservations WHERE experience_id IN(SELECT experience_id FROM experiences WHERE title LIKE 'record29-test-%')");
    jdbc.update("DELETE FROM experiences WHERE title LIKE 'record29-test-%'");
    jdbc.update(
        "DELETE FROM user_hobbies WHERE hobby_id IN(SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'record29-test-%')");
    jdbc.update(
        "DELETE FROM activities WHERE hobby_stage_id IN(SELECT hs.hobby_stage_id FROM hobby_stages hs JOIN hobbies h ON h.hobby_id=hs.hobby_id WHERE h.hobby_code LIKE 'record29-test-%')");
    jdbc.update(
        "DELETE FROM hobby_stages WHERE hobby_id IN(SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'record29-test-%')");
    jdbc.update("DELETE FROM hobbies WHERE hobby_code LIKE 'record29-test-%'");
    super.cleanupSeededHobbies(userId);
  }

  private record Activity(long id, long first, long second) {}

  private record Fixture(
      long owner,
      String token,
      long hobby,
      long stage,
      long tree,
      long record,
      long activity,
      long first,
      long second,
      int k) {}
}
