package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordListRepository;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class ActivityRecordListIntegrationTests extends IntegrationTestSupport {
  @MockitoSpyBean private ActivityRecordListRepository records;

  @Test
  void emptyOwnedTreeHasTheExactResponseAndDoesNotWrite() throws Exception {
    var f = fixture();
    var before = jdbc.queryForMap("SELECT * FROM user_hobbies WHERE user_hobby_id=?", f.tree());
    var response = get(f.token(), Long.toString(f.tree()), "");
    var body = success(response);
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder("userHobbyId", "items", "nextCursor", "hasNext");
    assertThat(body.path("userHobbyId").asLong()).isEqualTo(f.tree());
    assertThat(body.path("items").size()).isZero();
    assertThat(body.path("hasNext").asBoolean()).isFalse();
    assertThat(body.path("nextCursor").isNull()).isTrue();
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(jdbc.queryForMap("SELECT * FROM user_hobbies WHERE user_hobby_id=?", f.tree()))
        .isEqualTo(before);
  }

  @Test
  void realStartProgressPartialRepeatCompleteAndNotDoneRemainSeparateAttempts() throws Exception {
    var f = fixture();
    long first = start(f);
    var progress = progress(f, first, 1, List.of(f.steps().getFirst()));
    complete(f, first, progress.path("version").asLong(), "PARTIAL");
    long credited = start(f);
    progress = progress(f, credited, 1, f.steps());
    var receipt = complete(f, credited, progress.path("version").asLong(), "COMPLETED");
    assertThat(receipt.path("growth").path("growthApplied").asBoolean()).isTrue();
    long repeat = start(f);
    progress = progress(f, repeat, 1, f.steps());
    complete(f, repeat, progress.path("version").asLong(), "COMPLETED");
    long notDone = start(f);
    complete(f, notDone, 1, "NOT_DONE");
    long current = start(f);
    var before = state(f.tree());
    var body = success(get(f.token(), Long.toString(f.tree()), ""));
    assertThat(ids(body)).containsExactly(current, notDone, repeat, credited, first);
    var item = body.path("items").get(3);
    assertThat(item.propertyNames())
        .containsExactlyInAnyOrder(
            "recordId",
            "activityId",
            "title",
            "status",
            "progressPercent",
            "startedAt",
            "finishedAt",
            "satisfactionScore",
            "perceivedDifficulty",
            "growthApplied");
    assertThat(item.path("growthApplied").asBoolean()).isTrue();
    assertThat(item.path("satisfactionScore").asInt()).isEqualTo(4);
    assertThat(item.path("perceivedDifficulty").asText()).isEqualTo("APPROPRIATE");
    assertThat(item.path("progressPercent").asInt()).isEqualTo(100);
    assertThat(item.path("finishedAt")).isEqualTo(receipt.path("finishedAt"));
    assertThat(body.path("items").get(2).path("growthApplied").asBoolean()).isFalse();
    assertThat(body.path("items").get(4).path("progressPercent").asInt()).isEqualTo(33);
    assertThat(body.path("items").get(1).path("progressPercent").asInt()).isZero();
    assertThat(body.path("items").get(1).path("satisfactionScore").asInt()).isEqualTo(4);
    assertThat(body.path("items").get(0).path("finishedAt").isNull()).isTrue();
    assertThat(state(f.tree())).isEqualTo(before);
  }

  @Test
  void snapshotTitleAndRequiredFloorProgressSurviveLiveCatalogueChanges() throws Exception {
    var f = fixture();
    long record = start(f);
    progress(f, record, 1, List.of(f.steps().getFirst(), f.steps().getLast()));
    var original = success(get(f.token(), Long.toString(f.tree()), ""));
    jdbc.update(
        "UPDATE activities SET activity_name='changed',review_status='DRAFT' WHERE activity_id=?",
        f.activity());
    jdbc.update("UPDATE hobbies SET is_supported=false WHERE hobby_id=?", f.hobby());
    jdbc.update("DELETE FROM activity_steps WHERE activity_id=?", f.activity());
    var body = success(get(f.token(), Long.toString(f.tree()), ""));
    assertThat(body).isEqualTo(original);
    assertThat(body.path("items").get(0).path("title").asText()).isEqualTo("TEST ONLY record30");
    assertThat(body.path("items").get(0).path("progressPercent").asInt()).isEqualTo(33);
    assertThat(body.path("items").get(0).path("status").asText()).isEqualTo("IN_PROGRESS");
  }

  @Test
  void legacyNullsAndExperienceCreditAreIncludedWithoutInventedProgress() throws Exception {
    var f = fixture();
    long origin = legacy(f, "COMPLETED", null);
    jdbc.update(
        "UPDATE activity_records SET growth_counted=true,growth_stage_number=0 WHERE activity_record_id=?",
        origin);
    jdbc.update(
        "INSERT INTO activity_growth_contributions(user_hobby_id,activity_id,origin_record_id,credited_stage_number,source,credited_at) VALUES(?,?,?,0,'EXPERIENCE',now())",
        f.tree(),
        f.activity(),
        origin);
    long repeat = legacy(f, "COMPLETED", null);
    jdbc.update(
        "UPDATE activities SET review_status='DRAFT',activity_name='TEST ONLY legacy current' WHERE activity_id=?",
        f.activity());
    var before = state(f.tree());
    var body = success(get(f.token(), Long.toString(f.tree()), ""));
    assertThat(ids(body)).containsExactly(repeat, origin);
    for (var item : body.path("items")) {
      assertThat(item.path("progressPercent").isNull()).isTrue();
      assertThat(item.path("startedAt").isNull()).isTrue();
      assertThat(item.path("finishedAt").isNull()).isTrue();
      assertThat(item.path("satisfactionScore").isNull()).isTrue();
      assertThat(item.path("perceivedDifficulty").isNull()).isTrue();
      assertThat(item.path("title").asText()).isEqualTo("TEST ONLY legacy current");
    }
    assertThat(body.path("items").get(0).path("growthApplied").asBoolean()).isFalse();
    assertThat(body.path("items").get(1).path("growthApplied").asBoolean()).isTrue();
    assertThat(state(f.tree())).isEqualTo(before);
  }

  @Test
  void paginationPreservesMicrosecondsTiesAndTheNonNullToNullBoundary() throws Exception {
    var f = fixture();
    var time = Instant.parse("2026-11-19T01:02:03.123456Z");
    long lower = legacy(f, "PARTIAL", time.minusNanos(1000));
    long tie1 = legacy(f, "PARTIAL", time);
    long null1 = legacy(f, "PARTIAL", null);
    long tie2 = legacy(f, "PARTIAL", time);
    long null2 = legacy(f, "PARTIAL", null);
    var seen = new ArrayList<Long>();
    String cursor = null;
    for (int i = 0; i < 5; i++) {
      var body = success(get(f.token(), Long.toString(f.tree()), "size=1" + cursor(cursor)));
      seen.addAll(ids(body));
      assertThat(body.path("hasNext").asBoolean()).isEqualTo(i < 4);
      cursor = body.path("nextCursor").isNull() ? null : body.path("nextCursor").asText();
      if (i == 0)
        assertThat(Instant.parse(body.path("items").get(0).path("startedAt").asText()))
            .isEqualTo(time);
    }
    assertThat(seen).containsExactly(tie2, tie1, lower, null2, null1);
    assertThat(cursor).isNull();
  }

  @Test
  void sizeDefaultMaximumAndExactPageBoundaryUseOnlyTheNextMatchingRecord() throws Exception {
    var f = fixture();
    for (int i = 0; i < 51; i++) legacy(f, "PARTIAL", null);
    var body = success(get(f.token(), Long.toString(f.tree()), ""));
    assertThat(body.path("items").size()).isEqualTo(20);
    assertThat(body.path("hasNext").asBoolean()).isTrue();
    body = success(get(f.token(), Long.toString(f.tree()), "size=50"));
    assertThat(body.path("items").size()).isEqualTo(50);
    var next =
        success(
            get(
                f.token(),
                Long.toString(f.tree()),
                "size=1" + cursor(body.path("nextCursor").asText())));
    assertThat(next.path("items").size()).isEqualTo(1);
    assertThat(next.path("hasNext").asBoolean()).isFalse();
    assertThat(next.path("nextCursor").isNull()).isTrue();
    body = success(get(f.token(), Long.toString(f.tree()), "status=COMPLETED&size=1"));
    assertThat(body.path("items").size()).isZero();
    assertThat(body.path("hasNext").asBoolean()).isFalse();
  }

  @Test
  void eachStatusFiltersBeforeLimitAndEveryTreeStatusIsReadable() throws Exception {
    var f = fixture();
    for (var status : List.of("IN_PROGRESS", "COMPLETED", "PARTIAL", "NOT_DONE"))
      legacy(f, status, null);
    for (var treeStatus : List.of("ACTIVE", "PAUSED", "ENDED")) {
      jdbc.update(
          "UPDATE user_hobbies SET tree_status=? WHERE user_hobby_id=?", treeStatus, f.tree());
      for (var status : List.of("IN_PROGRESS", "COMPLETED", "PARTIAL", "NOT_DONE")) {
        var body = success(get(f.token(), Long.toString(f.tree()), "status=" + status + "&size=1"));
        assertThat(body.path("items").size()).isEqualTo(1);
        assertThat(body.path("items").get(0).path("status").asText()).isEqualTo(status);
        assertThat(body.path("hasNext").asBoolean()).isFalse();
      }
    }
  }

  @Test
  void nonOwnerAndMissingTreesHaveTheSame404AndNoPrivateResult() throws Exception {
    var f = fixture();
    legacy(f, "COMPLETED", null);
    var other = fixture();
    for (long tree : List.of(f.tree(), Long.MAX_VALUE)) {
      var response = get(other.token(), Long.toString(tree), "");
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(code(response)).isEqualTo("TREE404_1");
      assertThat(result(response).isNull()).isTrue();
    }
    assertThat(success(get(other.token(), Long.toString(other.tree()), "")).path("items").size())
        .isZero();
  }

  @Test
  void requiresServiceAuthenticationAndActiveMembership() throws Exception {
    var f = fixture();
    assertThat(code(get(null, Long.toString(f.tree()), ""))).isEqualTo("AUTH401_2");
    assertThat(code(get("invalid", Long.toString(f.tree()), ""))).isEqualTo("AUTH401_2");
    for (var entry :
        List.of(
            new String[] {"PENDING_ONBOARDING", "AUTH403_2"},
            new String[] {"SUSPENDED", "AUTH403_1"})) {
      jdbc.update("UPDATE users SET account_status=? WHERE user_id=?", entry[0], f.owner());
      assertThat(code(get(f.token(), Long.toString(f.tree()), ""))).isEqualTo(entry[1]);
    }
    jdbc.update(
        "UPDATE users SET account_status='WITHDRAWN',status_before_withdrawal='ACTIVE',withdrawn_at=now(),restorable_until=now()+interval '720 hours' WHERE user_id=?",
        f.owner());
    assertThat(code(get(f.token(), Long.toString(f.tree()), ""))).isEqualTo("AUTH403_3");
  }

  @Test
  void rejectsInvalidPathsStatusSizeAndCursorWithoutReturningRecords() throws Exception {
    var f = fixture();
    for (var path : List.of("0", "-1", "word", "1.5", "9223372036854775808"))
      bad(get(f.token(), path, ""));
    for (var query :
        List.of(
            "size=0",
            "size=51",
            "size=-1",
            "size=1.5",
            "size=",
            "size=NaN",
            "size=999999999999999999999",
            "status=",
            "status=ALL",
            "status=completed",
            "status=COMPLETED&status=PARTIAL",
            "size=1&size=2",
            "cursor=",
            "cursor=abc",
            "cursor=" + "a".repeat(513))) bad(get(f.token(), Long.toString(f.tree()), query));
  }

  @Test
  void cursorCannotBeReusedAcrossUsersTreesOrStatusFilters() throws Exception {
    var f = fixture();
    legacy(f, "PARTIAL", null);
    legacy(f, "PARTIAL", null);
    String all =
        success(get(f.token(), Long.toString(f.tree()), "size=1")).path("nextCursor").asText();
    String filtered =
        success(get(f.token(), Long.toString(f.tree()), "size=1&status=PARTIAL"))
            .path("nextCursor")
            .asText();
    bad(get(f.token(), Long.toString(f.tree()), "status=PARTIAL" + cursor(all)));
    bad(get(f.token(), Long.toString(f.tree()), cursor(filtered).substring(1)));
    bad(get(f.token(), Long.toString(f.tree()), "status=COMPLETED" + cursor(filtered)));
    long secondHobby =
        jdbc.queryForObject(
            "INSERT INTO hobbies(hobby_code,hobby_name) VALUES(?,'TEST ONLY') RETURNING hobby_id",
            Long.class,
            "record30-test-" + UUID.randomUUID());
    long secondTree =
        jdbc.queryForObject(
            "INSERT INTO user_hobbies(user_id,hobby_id) VALUES(?,?) RETURNING user_hobby_id",
            Long.class,
            f.owner(),
            secondHobby);
    bad(get(f.token(), Long.toString(secondTree), cursor(all).substring(1)));
    var other = fixture();
    bad(get(other.token(), Long.toString(other.tree()), cursor(all).substring(1)));
  }

  @Test
  void deletedAnchorDoesNotInvalidateTheCursorAndNewerInsertDoesNotDuplicateTheNextPage()
      throws Exception {
    var f = fixture();
    var time = Instant.parse("2026-11-19T01:02:03.123456Z");
    long tail = legacy(f, "PARTIAL", time.minusSeconds(1));
    long anchor = legacy(f, "PARTIAL", time);
    String token =
        success(get(f.token(), Long.toString(f.tree()), "size=1")).path("nextCursor").asText();
    jdbc.update("DELETE FROM activity_records WHERE activity_record_id=?", anchor);
    legacy(f, "PARTIAL", time.plusSeconds(1));
    var page = success(get(f.token(), Long.toString(f.tree()), "size=50" + cursor(token)));
    assertThat(ids(page)).containsExactly(tail);
    assertThat(page.path("hasNext").asBoolean()).isFalse();
  }

  @Test
  void nullRegionCursorAlsoSurvivesAnchorDeletion() throws Exception {
    var f = fixture();
    long tail = legacy(f, "PARTIAL", null);
    long anchor = legacy(f, "PARTIAL", null);
    String token =
        success(get(f.token(), Long.toString(f.tree()), "size=1")).path("nextCursor").asText();
    jdbc.update("DELETE FROM activity_records WHERE activity_record_id=?", anchor);
    legacy(f, "PARTIAL", null);
    assertThat(ids(success(get(f.token(), Long.toString(f.tree()), "size=1" + cursor(token)))))
        .containsExactly(tail);
  }

  @Test
  void oneReadSnapshotCannotMixPreCompletionProgressAndPostCompletionCredit() throws Exception {
    var f = fixture();
    long record = start(f);
    var reached = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              if (reached.getCount() > 0) {
                reached.countDown();
                assertThat(resume.await(8, TimeUnit.SECONDS)).isTrue();
              }
              return result;
            })
        .when(records)
        .ownsTree(f.owner(), f.tree());
    try (var executor = Executors.newSingleThreadExecutor()) {
      var response = executor.submit(() -> get(f.token(), Long.toString(f.tree()), ""));
      try {
        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue();
        var progress = progress(f, record, 1, f.steps());
        complete(f, record, progress.path("version").asLong(), "COMPLETED");
        resume.countDown();
        var old = success(response.get(10, TimeUnit.SECONDS)).path("items").get(0);
        assertThat(old.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(old.path("progressPercent").asInt()).isZero();
        assertThat(old.path("growthApplied").asBoolean()).isFalse();
        assertThat(old.path("satisfactionScore").isNull()).isTrue();
        var current = success(get(f.token(), Long.toString(f.tree()), "")).path("items").get(0);
        assertThat(current.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(current.path("progressPercent").asInt()).isEqualTo(100);
        assertThat(current.path("growthApplied").asBoolean()).isTrue();
        assertThat(current.path("satisfactionScore").asInt()).isEqualTo(4);
      } finally {
        resume.countDown();
      }
    }
  }

  private Fixture fixture() throws Exception {
    socialId = "record30-member-" + UUID.randomUUID();
    when(verifier.verify(any(), anyString())).thenReturn(socialId);
    var auth = result(login());
    long owner = auth.path("userId").asLong();
    createdUsers.add(owner);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", owner);
    long hobby =
        jdbc.queryForObject(
            "INSERT INTO hobbies(hobby_code,hobby_name,is_supported) VALUES(?,'TEST ONLY record30',true) RETURNING hobby_id",
            Long.class,
            "record30-test-" + UUID.randomUUID());
    long stage =
        jdbc.queryForObject(
            "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES(?,0,'TEST ONLY') RETURNING hobby_stage_id",
            Long.class,
            hobby);
    long activity =
        jdbc.queryForObject(
            "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status) VALUES(?,?,'TEST ONLY record30','PUBLISHED') RETURNING activity_id",
            Long.class,
            stage,
            "record30-test-" + UUID.randomUUID());
    var steps = new ArrayList<Long>();
    for (int i = 1; i <= 4; i++)
      steps.add(
          jdbc.queryForObject(
              "INSERT INTO activity_steps(activity_id,step_number,title,instruction,is_required) VALUES(?,?,'TEST ONLY step','TEST ONLY instruction',?) RETURNING activity_step_id",
              Long.class,
              activity,
              i,
              i != 4));
    long tree =
        jdbc.queryForObject(
            "INSERT INTO user_hobbies(user_id,hobby_id) VALUES(?,?) RETURNING user_hobby_id",
            Long.class,
            owner,
            hobby);
    return new Fixture(owner, auth.path("accessToken").asText(), hobby, activity, tree, steps);
  }

  private long legacy(Fixture f, String status, Instant at) {
    return jdbc.queryForObject(
        "INSERT INTO activity_records(user_hobby_id,activity_id,activity_status,started_at,step_progress,attempt_count) VALUES(?,?,?,?,?::jsonb,3) RETURNING activity_record_id",
        Long.class,
        f.tree(),
        f.activity(),
        status,
        at == null ? null : Timestamp.from(at),
        "{\"unknownLegacy\":true}");
  }

  private long start(Fixture f) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(base() + "/user-hobbies/" + f.tree() + "/activity-records"))
                .header("Authorization", "Bearer " + f.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"activityId\":" + f.activity() + "}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(201);
    return result(response).path("recordId").asLong();
  }

  private JsonNode progress(Fixture f, long record, long version, List<Long> steps)
      throws Exception {
    return patch(
        f,
        record,
        "progress",
        "{\"expectedVersion\":" + version + ",\"completedStepIds\":" + steps + "}");
  }

  private JsonNode complete(Fixture f, long record, long version, String status) throws Exception {
    return patch(
        f,
        record,
        "complete",
        "{\"expectedVersion\":"
            + version
            + ",\"status\":\""
            + status
            + "\",\"feedback\":{\"satisfactionScore\":4,\"perceivedDifficulty\":\"APPROPRIATE\",\"memo\":\"TEST ONLY\"},\"notDoneReason\":\"TEST ONLY reason\"}");
  }

  private JsonNode patch(Fixture f, long record, String action, String json) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(base() + "/activity-records/" + record + "/" + action))
                .header("Authorization", "Bearer " + f.token())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return result(response);
  }

  private HttpResponse<String> get(String token, String tree, String query) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    base()
                        + "/user-hobbies/"
                        + tree
                        + "/activity-records"
                        + (query.isEmpty() ? "" : "?" + query)))
            .timeout(Duration.ofSeconds(12))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String base() {
    return "http://127.0.0.1:" + port + "/api/v1";
  }

  private String cursor(String token) {
    return token == null ? "" : "&cursor=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
  }

  private String code(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("code").asText();
  }

  private JsonNode success(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(code(response)).isEqualTo("RECORD200_5");
    return result(response);
  }

  private void bad(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    assertThat(code(response)).isEqualTo("COMMON400_1");
    assertThat(result(response).isNull()).isTrue();
  }

  private List<Long> ids(JsonNode body) {
    var ids = new ArrayList<Long>();
    body.path("items").forEach(item -> ids.add(item.path("recordId").asLong()));
    return ids;
  }

  private List<?> state(long tree) {
    return jdbc.queryForList(
        "SELECT to_jsonb(r)::text AS state FROM activity_records r WHERE user_hobby_id=? ORDER BY activity_record_id",
        tree);
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update(
        "DELETE FROM user_hobbies WHERE hobby_id IN(SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'record30-test-%')");
    jdbc.update(
        "DELETE FROM activities WHERE hobby_stage_id IN(SELECT hs.hobby_stage_id FROM hobby_stages hs JOIN hobbies h ON h.hobby_id=hs.hobby_id WHERE h.hobby_code LIKE 'record30-test-%')");
    jdbc.update(
        "DELETE FROM hobby_stages WHERE hobby_id IN(SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'record30-test-%')");
    jdbc.update("DELETE FROM hobbies WHERE hobby_code LIKE 'record30-test-%'");
    super.cleanupSeededHobbies(userId);
  }

  private record Fixture(
      long owner, String token, long hobby, long activity, long tree, List<Long> steps) {}
}
