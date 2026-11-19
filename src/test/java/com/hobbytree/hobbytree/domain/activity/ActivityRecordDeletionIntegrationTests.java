package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordDeletionRepository;
import com.hobbytree.hobbytree.domain.experience.service.ExperienceAttendanceService;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class ActivityRecordDeletionIntegrationTests extends IntegrationTestSupport {
  @MockitoSpyBean private ActivityRecordDeletionRepository deletions;
  @Autowired private ExperienceAttendanceService attendances;

  @Test
  void deletesPartialAndNotDoneWithoutChangingGrowthAndPreservesOriginalFacts() throws Exception {
    var f = fixture(2);
    for (String status : List.of("PARTIAL", "NOT_DONE")) {
      long record = ended(f, f.activity(), status);
      String facts = facts(record);
      String snapshot =
          jdbc.queryForObject(
              "SELECT content_snapshot::text FROM activity_record_contents WHERE record_id=?",
              String.class,
              record);
      String receipt =
          jdbc.queryForObject(
              "SELECT response_payload::text FROM activity_completion_receipts WHERE record_id=?",
              String.class,
              record);
      var response = delete(f, record);
      var body = deleted(response);
      assertThat(body.propertyNames())
          .containsExactlyInAnyOrder(
              "recordId", "userHobbyId", "activityId", "deleted", "deletedAt", "growth");
      assertThat(body.path("recordId").asLong()).isEqualTo(record);
      assertThat(body.path("deleted").asBoolean()).isTrue();
      assertThat(body.path("growth").path("growthRevoked").asBoolean()).isFalse();
      assertThat(body.path("growth").path("distinctCompletedActivityCount").asLong()).isZero();
      assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
      assertThat(facts(record)).isEqualTo(facts);
      assertThat(
              jdbc.queryForObject(
                  "SELECT content_snapshot::text FROM activity_record_contents WHERE record_id=?",
                  String.class,
                  record))
          .isEqualTo(snapshot);
      assertThat(
              jdbc.queryForObject(
                  "SELECT response_payload::text FROM activity_completion_receipts WHERE record_id=?",
                  String.class,
                  record))
          .isEqualTo(receipt);
      assertThat(Instant.parse(body.path("deletedAt").asText()))
          .isAfterOrEqualTo(
              jdbc.queryForObject(
                      "SELECT finished_at FROM activity_records WHERE activity_record_id=?",
                      java.sql.Timestamp.class,
                      record)
                  .toInstant());
    }
    assertThat(count(f, "activity_record_deletions")).isEqualTo(2);
  }

  @Test
  void lastCompletionRevokesRebalancesCurrentBucketsAndKeepsHistoricalCreditFacts()
      throws Exception {
    var f = fixture(2);
    var origins = seedCredits(f, 6);
    var provenance =
        jdbc.queryForList(
            "SELECT contribution_id,credited_stage_number,source,credited_at,migrated_at FROM activity_growth_contributions WHERE user_hobby_id=? ORDER BY contribution_id",
            f.tree());
    var response = deleted(delete(f, origins.getFirst()));
    var growth = response.path("growth");
    assertThat(growth.path("growthRevoked").asBoolean()).isTrue();
    assertThat(growth.path("distinctCompletedActivityCount").asLong()).isEqualTo(5);
    assertThat(growth.path("previousStageNumber").asInt()).isEqualTo(4);
    assertThat(growth.path("currentStageNumber").asInt()).isEqualTo(3);
    assertThat(growth.path("stageChanged").asBoolean()).isTrue();
    assertThat(
            jdbc.queryForList(
                "SELECT contribution_id,credited_stage_number,source,credited_at,migrated_at FROM activity_growth_contributions WHERE user_hobby_id=? ORDER BY contribution_id",
                f.tree()))
        .isEqualTo(provenance.subList(1, 6));
    assertThat(
            jdbc.queryForList(
                "SELECT progress_stage_number::int FROM activity_growth_contributions WHERE user_hobby_id=? ORDER BY contribution_id",
                Integer.class,
                f.tree()))
        .containsExactly(0, 0, 1, 1, 2);
    assertTreeGrowth(f, 5, 3, 1);
    var roadmap = ok(get(f, "/user-hobbies/" + f.tree() + "/roadmap"));
    assertThat(roadmap.path("currentStageNumber").asInt()).isEqualTo(3);
    assertThat(roadmap.path("distinctCompletedActivityCount").asLong()).isEqualTo(5);
    assertThat(roadmap.path("stages").get(2).path("state").asText()).isEqualTo("CURRENT");
    long newRecord = ended(f, f.activity(), "COMPLETED");
    assertTreeGrowth(f, 6, 4, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT growth_counted FROM activity_records WHERE activity_record_id=?",
                Boolean.class,
                newRecord))
        .isTrue();
    // API-39 must use the redistributed current buckets too; this is an internal TEST ONLY path.
    long extra = activity(f.stages().getFirst());
    var attendance = attendances.applyAttendance(f.owner(), reservation(f, extra), f.tree());
    assertThat(attendance.growth().growthApplied()).isTrue();
    assertTreeGrowth(f, 7, 4, 1);
  }

  @Test
  void deletingTheOriginTransfersToTheSmallestSurvivorWithoutRewritingFirstCompletionResults()
      throws Exception {
    var f = fixture(3);
    long origin = ended(f, f.activity(), "COMPLETED");
    long survivor = ended(f, f.activity(), "COMPLETED");
    long latest = ended(f, f.activity(), "COMPLETED");
    var credit =
        jdbc.queryForMap(
            "SELECT * FROM activity_growth_contributions WHERE user_hobby_id=?", f.tree());
    var replayBefore = ok(patch(f, survivor, "complete", completion("COMPLETED", 99)));
    var body = deleted(delete(f, origin));
    assertThat(body.path("growth").path("growthRevoked").asBoolean()).isFalse();
    assertThat(body.path("growth").path("distinctCompletedActivityCount").asInt()).isEqualTo(1);
    var after =
        jdbc.queryForMap(
            "SELECT * FROM activity_growth_contributions WHERE user_hobby_id=?", f.tree());
    assertThat(after.get("origin_record_id")).isEqualTo(survivor);
    after.put("origin_record_id", credit.get("origin_record_id"));
    assertThat(after).isEqualTo(credit);
    assertThat(ok(patch(f, survivor, "complete", completion("COMPLETED", 1))))
        .isEqualTo(replayBefore);
    assertThat(replayBefore.path("growth").path("growthApplied").asBoolean()).isFalse();
    var audit =
        jdbc.queryForMap(
            "SELECT growth_action,growth_before::text AS before,growth_after::text AS after FROM activity_record_deletions WHERE record_id=?",
            origin);
    assertThat(audit.get("growth_action")).isEqualTo("REASSIGN");
    assertThat(
            mapper
                .readTree((String) audit.get("before"))
                .path("contributions")
                .get(0)
                .path("origin_record_id")
                .asLong())
        .isEqualTo(origin);
    assertThat(
            mapper
                .readTree((String) audit.get("after"))
                .path("contributions")
                .get(0)
                .path("origin_record_id")
                .asLong())
        .isEqualTo(survivor);
    assertThat(
            jdbc.queryForObject(
                "SELECT growth_counted FROM activity_records WHERE activity_record_id=?",
                Boolean.class,
                latest))
        .isFalse();
  }

  @Test
  void nonOriginDeletionKeepsTheCreditAndOnlyTheLastSurvivorRevokesIt() throws Exception {
    var f = fixture(1);
    long origin = ended(f, f.activity(), "COMPLETED");
    long repeat = ended(f, f.activity(), "COMPLETED");
    var before =
        jdbc.queryForMap(
            "SELECT * FROM activity_growth_contributions WHERE user_hobby_id=?", f.tree());
    assertThat(deleted(delete(f, repeat)).path("growth").path("growthRevoked").asBoolean())
        .isFalse();
    assertThat(
            jdbc.queryForMap(
                "SELECT * FROM activity_growth_contributions WHERE user_hobby_id=?", f.tree()))
        .isEqualTo(before);
    assertThat(deleted(delete(f, origin)).path("growth").path("growthRevoked").asBoolean())
        .isTrue();
    assertTreeGrowth(f, 0, 1, 0);
    long fresh = ended(f, f.activity(), "COMPLETED");
    assertThat(fresh).isNotIn(origin, repeat);
    assertTreeGrowth(f, 1, 2, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_records WHERE user_hobby_id=? AND deleted_at IS NOT NULL AND growth_counted",
                Integer.class,
                f.tree()))
        .isZero();
  }

  @Test
  void repeatedDeleteReturnsTheFrozenOriginalResultAfterLaterGrowth() throws Exception {
    var f = fixture(1);
    long first = ended(f, f.activity(), "COMPLETED");
    var initial = deleted(delete(f, first));
    ended(f, f.activity(), "COMPLETED");
    var before = state(f);
    assertThat(deleted(delete(f, first))).isEqualTo(initial);
    assertThat(state(f)).isEqualTo(before);
    assertThat(initial.path("growth").path("currentStageNumber").asInt()).isEqualTo(1);
    assertTreeGrowth(f, 1, 2, 0);
  }

  @Test
  void pausedAndEndedTreesAllowDeletionWithoutChangingLifecycleOrSlots() throws Exception {
    var f = fixture(3);
    for (String status : List.of("PAUSED", "ENDED")) {
      jdbc.update("UPDATE user_hobbies SET tree_status='ACTIVE' WHERE user_hobby_id=?", f.tree());
      long record = ended(f, f.activity(), "COMPLETED");
      jdbc.update("UPDATE user_hobbies SET tree_status=? WHERE user_hobby_id=?", status, f.tree());
      deleted(delete(f, record));
      assertThat(
              jdbc.queryForObject(
                  "SELECT tree_status FROM user_hobbies WHERE user_hobby_id=?",
                  String.class,
                  f.tree()))
          .isEqualTo(status);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM user_hobbies WHERE user_id=?", Integer.class, f.owner()))
          .isEqualTo(1);
    }
  }

  @Test
  void deletedRecordsAre404BeforeTreeStatusLegacySnapshotAndCompletionReplay() throws Exception {
    var f = fixture(3);
    long record = ended(f, f.activity(), "COMPLETED");
    deleted(delete(f, record));
    jdbc.update("UPDATE user_hobbies SET tree_status='PAUSED' WHERE user_hobby_id=?", f.tree());
    for (var response :
        List.of(
            get(f, "/activity-records/" + record),
            patch(f, record, "progress", "{\"expectedVersion\":1,\"completedStepIds\":[]}"),
            patch(f, record, "complete", completion("COMPLETED", 1)))) {
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(code(response)).isEqualTo("RECORD404_1");
      assertThat(result(response).isNull()).isTrue();
    }
    assertThat(count(f, "activity_completion_receipts")).isEqualTo(1);
    deleted(delete(f, record));
  }

  @Test
  void listFiltersDeletedRowsBeforeSizingAndCursorAnchorSurvivesLogicalDeletion() throws Exception {
    var f = fixture(3);
    long tail = legacy(f, f.activity(), "PARTIAL", false, 0);
    long middle = legacy(f, f.activity(), "PARTIAL", false, 0);
    long anchor = legacy(f, f.activity(), "PARTIAL", false, 0);
    var first = ok(get(f, listPath(f) + "?status=PARTIAL&size=1"));
    assertThat(first.path("items").get(0).path("recordId").asLong()).isEqualTo(anchor);
    deleted(delete(f, anchor));
    deleted(delete(f, middle));
    var next =
        ok(
            get(
                f,
                listPath(f)
                    + "?status=PARTIAL&size=1&cursor="
                    + URLEncoder.encode(
                        first.path("nextCursor").asText(), StandardCharsets.UTF_8)));
    assertThat(next.path("items").size()).isEqualTo(1);
    assertThat(next.path("items").get(0).path("recordId").asLong()).isEqualTo(tail);
    assertThat(next.path("hasNext").asBoolean()).isFalse();
    for (String status : List.of("COMPLETED", "NOT_DONE")) {
      long record = ended(f, f.activity(), status);
      deleted(delete(f, record));
      assertThat(ok(get(f, listPath(f) + "?status=" + status)).path("items").size()).isZero();
    }
    assertThat(ok(get(f, listPath(f))).path("items").size()).isEqualTo(1);
  }

  @Test
  void recentActivityTimeExcludesTheDeletionMutation() throws Exception {
    var f = fixture(3);
    long older = ended(f, f.activity(), "PARTIAL");
    long latest = ended(f, f.activity(), "PARTIAL");
    var expected =
        jdbc.queryForObject(
                "SELECT updated_at FROM activity_records WHERE activity_record_id=?",
                java.sql.Timestamp.class,
                older)
            .toInstant();
    deleted(delete(f, latest));
    var detail = ok(get(f, "/user-hobbies/" + f.tree()));
    assertThat(Instant.parse(detail.path("lastActivityAt").asText())).isEqualTo(expected);
  }

  @Test
  void snapshotlessTerminalRecordsCanBeDeletedButUnrepresentedCompletionFailsClosed()
      throws Exception {
    var f = fixture(3);
    long partial = legacy(f, f.activity(), "PARTIAL", false, 0);
    deleted(delete(f, partial));
    long bad = legacy(f, f.activity(), "COMPLETED", false, 0);
    var before = state(f);
    var response = delete(f, bad);
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(code(response)).isEqualTo("RECORD409_7");
    assertThat(state(f)).isEqualTo(before);
  }

  @Test
  void mirrorAndStageBaselineDisagreementsDoNotGuessOrPartiallyDelete() throws Exception {
    var f = fixture(2);
    long record = seedCredits(f, 2).getFirst();
    jdbc.update(
        "UPDATE activity_records SET growth_counted=false,growth_stage_number=NULL WHERE activity_record_id=?",
        record);
    var before = state(f);
    assertThat(code(delete(f, record))).isEqualTo("RECORD409_7");
    assertThat(state(f)).isEqualTo(before);
    jdbc.update(
        "UPDATE activity_records SET growth_counted=true,growth_stage_number=0 WHERE activity_record_id=?",
        record);
    jdbc.update("UPDATE user_hobbies SET current_stage_number=0 WHERE user_hobby_id=?", f.tree());
    before = state(f);
    assertThat(code(delete(f, record))).isEqualTo("RECORD409_7");
    assertThat(state(f)).isEqualTo(before);
  }

  @Test
  void inProgressIs409AndACompletionCanThenBeDeleted() throws Exception {
    var f = fixture(3);
    long record = start(f, f.activity());
    var before = state(f);
    assertThat(code(delete(f, record))).isEqualTo("RECORD409_6");
    assertThat(state(f)).isEqualTo(before);
    ok(patch(f, record, "complete", completion("PARTIAL", 1)));
    deleted(delete(f, record));
  }

  @Test
  void verifiesAuthMembershipOwnerAndPositivePathEvenForDeletedRecords() throws Exception {
    var f = fixture(3);
    long record = ended(f, f.activity(), "PARTIAL");
    deleted(delete(f, record));
    var other = fixture(3);
    for (long id : List.of(record, Long.MAX_VALUE)) {
      var response = request(other.token(), "DELETE", "/activity-records/" + id, null);
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(code(response)).isEqualTo("RECORD404_1");
      assertThat(result(response).isNull()).isTrue();
    }
    assertThat(code(request(null, "DELETE", "/activity-records/" + record, null)))
        .isEqualTo("AUTH401_2");
    for (String path : List.of("0", "-1", "word", "1.5", "9223372036854775808"))
      assertThat(code(request(f.token(), "DELETE", "/activity-records/" + path, null)))
          .isEqualTo("COMMON400_1");
    for (var entry :
        List.of(
            new String[] {"PENDING_ONBOARDING", "AUTH403_2"},
            new String[] {"SUSPENDED", "AUTH403_1"})) {
      jdbc.update("UPDATE users SET account_status=? WHERE user_id=?", entry[0], f.owner());
      assertThat(code(delete(f, record))).isEqualTo(entry[1]);
    }
    jdbc.update(
        "UPDATE users SET account_status='WITHDRAWN',status_before_withdrawal='ACTIVE',withdrawn_at=now(),restorable_until=now()+interval '720 hours' WHERE user_id=?",
        f.owner());
    assertThat(code(delete(f, record))).isEqualTo("AUTH403_3");
  }

  @Test
  void concurrentSameDeleteReplaysOneResultAndRevokesOnlyOnce() throws Exception {
    var f = fixture(1);
    long record = ended(f, f.activity(), "COMPLETED");
    try (var executor = Executors.newFixedThreadPool(2)) {
      var go = new CountDownLatch(1);
      var a =
          executor.submit(
              () -> {
                go.await();
                return delete(f, record);
              });
      var b =
          executor.submit(
              () -> {
                go.await();
                return delete(f, record);
              });
      go.countDown();
      assertThat(deleted(a.get(12, TimeUnit.SECONDS)))
          .isEqualTo(deleted(b.get(12, TimeUnit.SECONDS)));
    }
    assertThat(count(f, "activity_record_deletions")).isEqualTo(1);
    assertTreeGrowth(f, 0, 1, 0);
  }

  @Test
  void concurrentDifferentCompletionDeletesLeaveNoCreditAndExactlyOneRevocation() throws Exception {
    var f = fixture(1);
    long a = ended(f, f.activity(), "COMPLETED");
    long b = ended(f, f.activity(), "COMPLETED");
    try (var executor = Executors.newFixedThreadPool(2)) {
      var go = new CountDownLatch(1);
      var one =
          executor.submit(
              () -> {
                go.await();
                return delete(f, a);
              });
      var two =
          executor.submit(
              () -> {
                go.await();
                return delete(f, b);
              });
      go.countDown();
      var results =
          List.of(deleted(one.get(12, TimeUnit.SECONDS)), deleted(two.get(12, TimeUnit.SECONDS)));
      assertThat(
              results.stream()
                  .filter(n -> n.path("growth").path("growthRevoked").asBoolean())
                  .count())
          .isEqualTo(1);
    }
    assertThat(count(f, "activity_record_deletions")).isEqualTo(2);
    assertTreeGrowth(f, 0, 1, 0);
  }

  @Test
  void lastDeletionAndNewCompletionRacePreservesExactlyOneLiveCredit() throws Exception {
    var f = fixture(1);
    long original = ended(f, f.activity(), "COMPLETED");
    long next = start(f, f.activity());
    ready(f, next);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var go = new CountDownLatch(1);
      var one =
          executor.submit(
              () -> {
                go.await();
                return delete(f, original);
              });
      var two =
          executor.submit(
              () -> {
                go.await();
                return patch(f, next, "complete", completion("COMPLETED", 2));
              });
      go.countDown();
      deleted(one.get(12, TimeUnit.SECONDS));
      ok(two.get(12, TimeUnit.SECONDS));
    }
    assertTreeGrowth(f, 1, 2, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT origin_record_id FROM activity_growth_contributions WHERE user_hobby_id=?",
                Long.class,
                f.tree()))
        .isEqualTo(next);
  }

  @Test
  void deletionWaitsForCompletionAndThenSeesItsCommittedTerminalState() throws Exception {
    var f = fixture(1);
    long record = start(f, f.activity());
    ready(f, record);
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var stmt =
          connection.prepareStatement("SELECT user_id FROM users WHERE user_id=? FOR UPDATE")) {
        stmt.setLong(1, f.owner());
        stmt.executeQuery().close();
      }
      int blocker;
      try (var stmt = connection.createStatement();
          var row = stmt.executeQuery("SELECT pg_backend_pid()")) {
        row.next();
        blocker = row.getInt(1);
      }
      try (var executor = Executors.newSingleThreadExecutor()) {
        var response = executor.submit(() -> delete(f, record));
        boolean blocked = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
          blocked =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))",
                      Boolean.class,
                      blocker));
          if (blocked) break;
          Thread.sleep(20);
        }
        assertThat(blocked).as("DELETE waits on the actual user-row lock").isTrue();
        assertThat(response.isDone()).isFalse();
        // Simulate the same terminal transition under the already-held shared user/tree protocol.
        try (var stmt =
            connection.prepareStatement(
                "UPDATE activity_records SET activity_status='PARTIAL',finished_at=clock_timestamp(),record_version=3,satisfaction=4,perceived_difficulty='APPROPRIATE' WHERE activity_record_id=?")) {
          stmt.setLong(1, record);
          stmt.executeUpdate();
        }
        connection.commit();
        deleted(response.get(12, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void alreadyStartedHighStageSnapshotSurvivesStageDropAndCanStillComplete() throws Exception {
    var f = fixture(1);
    var old = seedCredits(f, 3);
    long high = activity(f.stages().get(3));
    long record = start(f, high);
    String snapshot =
        jdbc.queryForObject(
            "SELECT content_snapshot::text FROM activity_record_contents WHERE record_id=?",
            String.class,
            record);
    deleted(delete(f, old.getFirst()));
    assertTreeGrowth(f, 2, 3, 0);
    assertThat(ok(get(f, "/activity-records/" + record)).path("canUpdate").asBoolean()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT content_snapshot::text FROM activity_record_contents WHERE record_id=?",
                String.class,
                record))
        .isEqualTo(snapshot);
    ready(f, record);
    ok(patch(f, record, "complete", completion("COMPLETED", 2)));
    assertTreeGrowth(f, 3, 4, 0);
  }

  @Test
  void newHighStageStartsFollowTheLoweredUnlockStage() throws Exception {
    var f = fixture(1);
    var old = seedCredits(f, 3);
    long high = activity(f.stages().get(3));
    deleted(delete(f, old.getFirst()));
    var response = request(f.token(), "POST", listPath(f), "{\"activityId\":" + high + "}");
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(code(response)).isEqualTo("ACTIVITY403_1");
  }

  @Test
  void maximumStageCanBeRetainedOrLoweredUsingTheHobbyRule() throws Exception {
    var f = fixture(1);
    var origins = seedCredits(f, 6);
    var first = deleted(delete(f, origins.getFirst())).path("growth");
    assertThat(first.path("isMaxStage").asBoolean()).isTrue();
    assertThat(first.path("stageChanged").asBoolean()).isFalse();
    var second = deleted(delete(f, origins.get(1))).path("growth");
    assertThat(second.path("isMaxStage").asBoolean()).isFalse();
    assertThat(second.path("currentStageNumber").asInt()).isEqualTo(5);
    assertTreeGrowth(f, 4, 5, 0);
  }

  @Test
  void failureAfterReceiptInsertRollsBackEveryChangeAndAllowsAFreshRetry() throws Exception {
    var f = fixture(2);
    long record = seedCredits(f, 4).getFirst();
    var before = state(f);
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("TEST ONLY rollback after receipt");
            })
        .when(deletions)
        .save(anyLong(), any(), anyString(), anyString(), anyString(), anyString());
    assertThat(code(delete(f, record))).isEqualTo("COMMON500_1");
    assertThat(state(f)).isEqualTo(before);
    doCallRealMethod()
        .when(deletions)
        .save(anyLong(), any(), anyString(), anyString(), anyString(), anyString());
    deleted(delete(f, record));
    assertTreeGrowth(f, 3, 2, 1);
  }

  @Test
  void deletionReceiptsAndDeletedRecordsCannotBeMutatedButTreePurgeStillWorks() throws Exception {
    var f = fixture(3);
    long record = ended(f, f.activity(), "PARTIAL");
    deleted(delete(f, record));
    for (String sql :
        List.of(
            "UPDATE activity_record_deletions SET growth_action='REVOKE' WHERE record_id=?",
            "DELETE FROM activity_record_deletions WHERE record_id=?",
            "UPDATE activity_records SET deleted_at=NULL WHERE activity_record_id=?",
            "UPDATE activity_records SET feedback_memo='changed' WHERE activity_record_id=?"))
      assertThatThrownBy(() -> jdbc.update(sql, record))
          .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update("DELETE FROM user_hobbies WHERE user_hobby_id=?", f.tree());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_record_deletions WHERE record_id=?",
                Integer.class,
                record))
        .isZero();
  }

  @Test
  void attendanceReplayNeverRestoresDeletedRecordsOrRecreatesRevokedCredit() throws Exception {
    var f = fixture(3);
    long reservation = reservation(f, f.activity());
    var first = attendances.applyAttendance(f.owner(), reservation, f.tree());
    long record = first.recordId();
    deleted(delete(f, record));
    var before = state(f);
    for (int i = 0; i < 2; i++) {
      var response =
          request(
              f.token(),
              "POST",
              "/experience-reservations/" + reservation + "/attendance",
              "{\"userHobbyId\":"
                  + f.tree()
                  + ",\"attendanceToken\":\"TEST ONLY already attended\"}",
              "deletion-replay-" + i);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      assertThat(result(response).path("growth").path("growthApplied").asBoolean()).isFalse();
    }
    assertThat(state(f)).isEqualTo(before);
    var fresh = attendances.applyAttendance(f.owner(), reservation(f, f.activity()), f.tree());
    assertThat(fresh.recordId()).isNotEqualTo(record);
    assertTreeGrowth(f, 1, 1, 1);
  }

  @Test
  void attendanceWithoutItsOwnCreditFkCanReplayHistoricalConfirmationAfterRevocation()
      throws Exception {
    var f = fixture(3);
    long record = ended(f, f.activity(), "COMPLETED");
    long reservation = reservation(f, f.activity());
    assertThat(
            attendances.applyAttendance(f.owner(), reservation, f.tree()).growth().growthApplied())
        .isFalse();
    deleted(delete(f, record));
    var before = state(f);
    var response =
        request(
            f.token(),
            "POST",
            "/experience-reservations/" + reservation + "/attendance",
            "{\"userHobbyId\":" + f.tree() + ",\"attendanceToken\":\"TEST ONLY already attended\"}",
            "deletion-no-fk");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(state(f)).isEqualTo(before);
  }

  private Fixture fixture(int k) throws Exception {
    socialId = "record50-member-" + UUID.randomUUID();
    when(verifier.verify(any(), anyString())).thenReturn(socialId);
    var auth = result(login());
    long owner = auth.path("userId").asLong();
    createdUsers.add(owner);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", owner);
    long hobby =
        jdbc.queryForObject(
            "INSERT INTO hobbies(hobby_code,hobby_name,is_supported,activities_per_stage) VALUES(?,'TEST ONLY record50',true,?) RETURNING hobby_id",
            Long.class,
            "record50-test-" + UUID.randomUUID(),
            k);
    var stages = new ArrayList<Long>();
    for (int i = 0; i <= 5; i++)
      stages.add(
          jdbc.queryForObject(
              "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES(?,?,'TEST ONLY') RETURNING hobby_stage_id",
              Long.class,
              hobby,
              i));
    long tree =
        jdbc.queryForObject(
            "INSERT INTO user_hobbies(user_id,hobby_id) VALUES(?,?) RETURNING user_hobby_id",
            Long.class,
            owner,
            hobby);
    return new Fixture(
        owner,
        auth.path("accessToken").asText(),
        hobby,
        stages,
        tree,
        activity(stages.getFirst()),
        k);
  }

  private long activity(long stage) {
    long activity =
        jdbc.queryForObject(
            "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status) VALUES(?,?,'TEST ONLY record50','PUBLISHED') RETURNING activity_id",
            Long.class,
            stage,
            "record50-test-" + UUID.randomUUID());
    for (int i = 1; i <= 2; i++)
      jdbc.update(
          "INSERT INTO activity_steps(activity_id,step_number,title,instruction,is_required) VALUES(?,?,'TEST ONLY step','TEST ONLY instruction',true)",
          activity,
          i);
    return activity;
  }

  private long start(Fixture f, long activity) throws Exception {
    var response = request(f.token(), "POST", listPath(f), "{\"activityId\":" + activity + "}");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return result(response).path("recordId").asLong();
  }

  private long ended(Fixture f, long activity, String status) throws Exception {
    long record = start(f, activity);
    if ("COMPLETED".equals(status)) ready(f, record);
    ok(patch(f, record, "complete", completion(status, "COMPLETED".equals(status) ? 2 : 1)));
    return record;
  }

  private void ready(Fixture f, long record) throws Exception {
    var steps =
        jdbc.queryForList(
            "SELECT step_id FROM activity_record_steps WHERE record_id=? ORDER BY step_number",
            Long.class,
            record);
    ok(patch(f, record, "progress", "{\"expectedVersion\":1,\"completedStepIds\":" + steps + "}"));
  }

  private String completion(String status, long version) {
    return "{\"status\":\""
        + status
        + "\",\"expectedVersion\":"
        + version
        + ",\"feedback\":{\"satisfactionScore\":4,\"perceivedDifficulty\":\"APPROPRIATE\",\"memo\":\" TEST ONLY 🌱 \"},\"notDoneReason\":\"TEST ONLY\"}";
  }

  private long legacy(Fixture f, long activity, String status, boolean growth, int stage) {
    return jdbc.queryForObject(
        "INSERT INTO activity_records(user_hobby_id,activity_id,activity_status,growth_counted,growth_stage_number) VALUES(?,?,?,?,?) RETURNING activity_record_id",
        Long.class,
        f.tree(),
        activity,
        status,
        growth,
        growth ? stage : null);
  }

  private List<Long> seedCredits(Fixture f, int count) {
    var records = new ArrayList<Long>();
    for (int i = 0; i < count; i++) {
      long activity = activity(f.stages().getFirst());
      int stage = Math.min(5, i / f.k());
      long id = legacy(f, activity, "COMPLETED", true, stage);
      records.add(id);
      jdbc.update(
          "INSERT INTO activity_growth_contributions(user_hobby_id,activity_id,origin_record_id,credited_stage_number,source,migrated_at) VALUES(?,?,?,?,'LEGACY_RECORD',clock_timestamp())",
          f.tree(),
          activity,
          id,
          stage);
    }
    jdbc.update(
        "UPDATE user_hobbies SET current_stage_number=? WHERE user_hobby_id=?",
        Math.min(5, count / f.k()),
        f.tree());
    return records;
  }

  private long reservation(Fixture f, long activity) {
    long experience =
        jdbc.queryForObject(
            "INSERT INTO experiences(experience_type,title,listing_status,hobby_id,linked_activity_id) VALUES('CLASS',?,'OPEN',?,?) RETURNING experience_id",
            Long.class,
            "record50-test-" + UUID.randomUUID(),
            f.hobby(),
            activity);
    return jdbc.queryForObject(
        "INSERT INTO experience_reservations(reservation_code,experience_id,user_id,reservation_status) VALUES(?,?,?,'CONFIRMED') RETURNING reservation_id",
        Long.class,
        "record50-test-" + UUID.randomUUID(),
        experience,
        f.owner());
  }

  private HttpResponse<String> delete(Fixture f, long record) throws Exception {
    return request(f.token(), "DELETE", "/activity-records/" + record, null);
  }

  private HttpResponse<String> get(Fixture f, String path) throws Exception {
    return request(f.token(), "GET", path, null);
  }

  private HttpResponse<String> patch(Fixture f, long record, String action, String json)
      throws Exception {
    return request(f.token(), "PATCH", "/activity-records/" + record + "/" + action, json);
  }

  private HttpResponse<String> request(String token, String method, String path, String body)
      throws Exception {
    return request(token, method, path, body, null);
  }

  private HttpResponse<String> request(
      String token, String method, String path, String body, String idempotency) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
            .timeout(Duration.ofSeconds(15))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body != null) request.header("Content-Type", "application/json");
    if (idempotency != null) request.header("Idempotency-Key", idempotency);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String listPath(Fixture f) {
    return "/user-hobbies/" + f.tree() + "/activity-records";
  }

  private String code(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("code").asText();
  }

  private JsonNode ok(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return result(response);
  }

  private JsonNode deleted(HttpResponse<String> response) {
    var body = ok(response);
    assertThat(code(response)).isEqualTo("RECORD200_6");
    return body;
  }

  private String facts(long record) {
    return jdbc.queryForObject(
        "SELECT (to_jsonb(r)-ARRAY['deleted_at','updated_at','growth_counted','growth_stage_number'])::text FROM activity_records r WHERE activity_record_id=?",
        String.class,
        record);
  }

  private String state(Fixture f) {
    return jdbc.queryForObject(
        "SELECT jsonb_build_object('tree',to_jsonb(t),'records',(SELECT jsonb_agg(to_jsonb(r) ORDER BY activity_record_id) FROM activity_records r WHERE r.user_hobby_id=t.user_hobby_id),'growth',(SELECT jsonb_agg(to_jsonb(g) ORDER BY contribution_id) FROM activity_growth_contributions g WHERE g.user_hobby_id=t.user_hobby_id),'deletions',(SELECT jsonb_agg(to_jsonb(d) ORDER BY record_id) FROM activity_record_deletions d JOIN activity_records r ON r.activity_record_id=d.record_id WHERE r.user_hobby_id=t.user_hobby_id))::text FROM user_hobbies t WHERE t.user_hobby_id=?",
        String.class,
        f.tree());
  }

  private int count(Fixture f, String table) {
    if (!List.of("activity_record_deletions", "activity_completion_receipts").contains(table))
      throw new IllegalArgumentException();
    return jdbc.queryForObject(
        "SELECT count(*) FROM "
            + table
            + " d JOIN activity_records r ON r.activity_record_id=d.record_id WHERE r.user_hobby_id=?",
        Integer.class,
        f.tree());
  }

  private void assertTreeGrowth(Fixture f, int total, int externalStage, int currentProgress)
      throws Exception {
    var detail = ok(get(f, "/user-hobbies/" + f.tree()));
    assertThat(detail.path("distinctCompletedActivityCount").asInt()).isEqualTo(total);
    assertThat(detail.path("currentStageNumber").asInt()).isEqualTo(externalStage);
    assertThat(detail.path("currentStageCompletedCount").asInt()).isEqualTo(currentProgress);
    var item = ok(get(f, "/user-hobbies")).path("items").get(0);
    assertThat(item.path("distinctCompletedActivityCount").asInt()).isEqualTo(total);
    assertThat(item.path("currentStageNumber").asInt()).isEqualTo(externalStage);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_records r LEFT JOIN activity_growth_contributions g ON g.origin_record_id=r.activity_record_id WHERE r.user_hobby_id=? AND ((r.growth_counted AND (g.contribution_id IS NULL OR r.growth_stage_number IS DISTINCT FROM COALESCE(g.progress_stage_number,g.credited_stage_number))) OR (g.contribution_id IS NOT NULL AND (NOT r.growth_counted OR r.deleted_at IS NOT NULL)))",
                Integer.class,
                f.tree()))
        .isZero();
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update(
        "DELETE FROM experience_reservations WHERE experience_id IN(SELECT experience_id FROM experiences WHERE title LIKE 'record50-test-%')");
    jdbc.update("DELETE FROM experiences WHERE title LIKE 'record50-test-%'");
    jdbc.update(
        "DELETE FROM user_hobbies WHERE hobby_id IN(SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'record50-test-%')");
    jdbc.update(
        "DELETE FROM activities WHERE hobby_stage_id IN(SELECT s.hobby_stage_id FROM hobby_stages s JOIN hobbies h ON h.hobby_id=s.hobby_id WHERE h.hobby_code LIKE 'record50-test-%')");
    jdbc.update(
        "DELETE FROM hobby_stages WHERE hobby_id IN(SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'record50-test-%')");
    jdbc.update("DELETE FROM hobbies WHERE hobby_code LIKE 'record50-test-%'");
    super.cleanupSeededHobbies(userId);
  }

  private record Fixture(
      long owner, String token, long hobby, List<Long> stages, long tree, long activity, int k) {}
}
