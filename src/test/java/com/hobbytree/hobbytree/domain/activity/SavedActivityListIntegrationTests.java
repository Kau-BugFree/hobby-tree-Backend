package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.activity.service.SavedActivityCursor;
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
class SavedActivityListIntegrationTests extends IntegrationTestSupport {
  @MockitoSpyBean private SavedActivityCursor cursors;
  private static final Instant AT = Instant.parse("2026-11-19T00:00:00.123456Z");

  @Test
  void emptyResultHasExactShapeAndNoStoreWithoutCreatingAnything() throws Exception {
    var f = fixture();
    var before = state(f);
    var response = query(f, "");
    var body = success(response);
    assertThat(body.propertyNames()).containsExactlyInAnyOrder("items", "nextCursor", "hasNext");
    assertThat(body.path("items").size()).isZero();
    assertLast(body);
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(state(f)).isEqualTo(before);
  }

  @Test
  void returnsAllAvailableFieldsAndUsesStoredSummaryAndHobbyImageFallback() throws Exception {
    var f = fixture();
    bookmark(f, f.activity(), AT);
    var before = state(f);
    var item = success(query(f, "")).path("items").get(0);
    assertThat(item.propertyNames())
        .containsExactlyInAnyOrder(
            "activityId",
            "title",
            "thumbnailUrl",
            "durationMinutes",
            "estimatedCost",
            "difficulty",
            "indoorOutdoor",
            "isSaved",
            "hobbyId",
            "hobbyName",
            "stageId",
            "stageNumber",
            "savedAt",
            "isAvailable");
    assertThat(item.path("activityId").asLong()).isEqualTo(f.activity());
    assertThat(item.path("title").asText()).isEqualTo("TEST ONLY saved33 activity");
    assertThat(item.path("thumbnailUrl").asText())
        .isEqualTo("https://example.invalid/saved33-hobby.png");
    assertThat(item.path("durationMinutes").asInt()).isEqualTo(42);
    assertThat(item.path("estimatedCost").asInt()).isEqualTo(100);
    assertThat(item.path("difficulty").asText()).isEqualTo("BEGINNER");
    assertThat(item.path("indoorOutdoor").asText()).isEqualTo("OUTDOOR");
    assertThat(item.path("isSaved").asBoolean()).isTrue();
    assertThat(item.path("isAvailable").asBoolean()).isTrue();
    assertThat(item.path("hobbyId").asLong()).isEqualTo(f.hobby());
    assertThat(item.path("hobbyName").asText()).isEqualTo("TEST ONLY saved33 hobby");
    assertThat(item.path("stageId").asLong()).isEqualTo(f.stage());
    assertThat(item.path("stageNumber").asInt()).isEqualTo(0);
    assertThat(Instant.parse(item.path("savedAt").asText())).isEqualTo(AT);
    assertThat(state(f)).isEqualTo(before);
    jdbc.update(
        "UPDATE activities SET thumbnail_url='https://example.invalid/saved33-activity.png' WHERE activity_id=?",
        f.activity());
    assertThat(success(query(f, "")).path("items").get(0).path("thumbnailUrl").asText())
        .endsWith("saved33-activity.png");
  }

  @Test
  void availableNullableSummaryFieldsRemainUnknownWithoutEstimation() throws Exception {
    var f = fixture();
    bookmark(f, f.activity(), AT);
    jdbc.update(
        "UPDATE activities SET expected_total_minutes=NULL,estimated_cost_min=NULL,estimated_cost_max=NULL,difficulty=NULL WHERE activity_id=?",
        f.activity());
    jdbc.update("UPDATE hobbies SET thumbnail_url=NULL WHERE hobby_id=?", f.hobby());
    var item = success(query(f, "")).path("items").get(0);
    assertThat(item.path("isAvailable").asBoolean()).isTrue();
    for (String field : List.of("durationMinutes", "estimatedCost", "difficulty", "thumbnailUrl"))
      assertThat(item.path(field).isNull()).as(field).isTrue();
  }

  @Test
  void allHiddenStatesAndUnsupportedHobbiesExposeOnlyFourPermittedFields() throws Exception {
    var f = fixture();
    bookmark(f, f.activity(), AT);
    for (String status : List.of("DRAFT", "REVIEW", "REJECTED")) {
      jdbc.update(
          "UPDATE activities SET review_status=?,activity_name='PRIVATE TEST ONLY TITLE',detailed_description='PRIVATE TEST ONLY BODY' WHERE activity_id=?",
          status,
          f.activity());
      var response = query(f, "");
      unavailable(success(response).path("items").get(0), f.activity());
      assertThat(response.body())
          .doesNotContain("PRIVATE TEST ONLY", "saved33-hobby.png", "OUTDOOR");
    }
    jdbc.update(
        "UPDATE activities SET review_status='PUBLISHED' WHERE activity_id=?", f.activity());
    jdbc.update("UPDATE hobbies SET is_supported=false WHERE hobby_id=?", f.hobby());
    unavailable(success(query(f, "hobbyId=" + f.hobby())).path("items").get(0), f.activity());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_activities WHERE user_id=?", Integer.class, f.owner()))
        .isEqualTo(1);
  }

  @Test
  void microsecondOrderingAndTimestampTiesContinueWithoutDuplicates() throws Exception {
    var f = fixture();
    long older = activity(f.stage());
    bookmark(f, older, AT.minusNanos(1000));
    long tie1 = activity(f.stage());
    bookmark(f, tie1, AT);
    long tie2 = activity(f.stage());
    bookmark(f, tie2, AT);
    long newer = activity(f.stage());
    bookmark(f, newer, AT.plusNanos(1000));
    var seen = new ArrayList<Long>();
    String cursor = null;
    for (int i = 0; i < 4; i++) {
      var body = success(query(f, "size=1" + cursor(cursor)));
      seen.addAll(ids(body));
      if (i < 3) {
        assertThat(body.path("hasNext").asBoolean()).isTrue();
        cursor = body.path("nextCursor").asText();
      } else assertLast(body);
    }
    assertThat(seen).containsExactly(newer, tie2, tie1, older);
  }

  @Test
  void filtersBeforeLimitAndSupportsDefaultMaximumAndExactLastPages() throws Exception {
    var f = fixture();
    for (int i = 0; i < 51; i++) bookmark(f, activity(f.stage()), AT);
    long otherHobby = hobby();
    long otherStage = stage(otherHobby, 0);
    bookmark(f, activity(otherStage), AT.plusSeconds(1));
    var defaults = success(query(f, ""));
    assertThat(defaults.path("items").size()).isEqualTo(20);
    var first = success(query(f, "hobbyId=" + f.hobby() + "&size=50"));
    assertThat(first.path("items").size()).isEqualTo(50);
    assertThat(first.path("hasNext").asBoolean()).isTrue();
    var last =
        success(
            query(
                f, "hobbyId=" + f.hobby() + "&size=1" + cursor(first.path("nextCursor").asText())));
    assertThat(last.path("items").size()).isEqualTo(1);
    assertLast(last);
    var empty = success(query(f, "hobbyId=" + Long.MAX_VALUE));
    assertThat(empty.path("items").size()).isZero();
    assertLast(empty);
  }

  @Test
  void userAndHobbyBoundCursorsCannotBeReusedAndForeignBookmarksNeverAppear() throws Exception {
    var f = fixture();
    bookmark(f, f.activity(), AT);
    bookmark(f, activity(f.stage()), AT);
    var filtered =
        success(query(f, "hobbyId=" + f.hobby() + "&size=1")).path("nextCursor").asText();
    bad(query(f, "size=1" + cursor(filtered)));
    bad(query(f, "hobbyId=" + Long.MAX_VALUE + cursor(filtered)));
    var all = success(query(f, "size=1")).path("nextCursor").asText();
    var other = fixture();
    assertThat(success(query(other, "")).path("items").size()).isZero();
    bad(query(other, "size=1" + cursor(all)));
    bookmark(other, f.activity(), AT.plusSeconds(1));
    assertThat(ids(success(query(other, "")))).containsExactly(f.activity());
    assertThat(success(query(f, "")).path("items").size()).isEqualTo(2);
  }

  @Test
  void invalidEmptyRepeatedAndMalformedInputsUseCommon400() throws Exception {
    var f = fixture();
    for (String query :
        List.of(
            "size=",
            "size=0",
            "size=51",
            "size=-1",
            "size=1.5",
            "size=abc",
            "size=9999999999999",
            "size=1&size=2",
            "hobbyId=",
            "hobbyId=0",
            "hobbyId=-1",
            "hobbyId=1.5",
            "hobbyId=x",
            "hobbyId=9223372036854775808",
            "hobbyId=1&hobbyId=2",
            "cursor=",
            "cursor=x.y",
            "cursor=" + "a".repeat(257))) bad(query(f, query));
  }

  @Test
  void authenticationAndAllInactiveAccountStatesAreRejected() throws Exception {
    var f = fixture();
    bookmark(f, f.activity(), AT);
    assertThat(code(request(null, "GET", path(), null))).isEqualTo("AUTH401_2");
    assertThat(code(request("invalid", "GET", path(), null))).isEqualTo("AUTH401_2");
    for (var entry :
        List.of(
            new String[] {"PENDING_ONBOARDING", "AUTH403_2"},
            new String[] {"SUSPENDED", "AUTH403_1"})) {
      jdbc.update("UPDATE users SET account_status=? WHERE user_id=?", entry[0], f.owner());
      assertThat(code(query(f, ""))).isEqualTo(entry[1]);
    }
    jdbc.update(
        "UPDATE users SET account_status='WITHDRAWN',status_before_withdrawal='ACTIVE',withdrawn_at=now(),restorable_until=now()+interval '720 hours' WHERE user_id=?",
        f.owner());
    assertThat(code(query(f, ""))).isEqualTo("AUTH403_3");
  }

  @Test
  void unsavingTheAnchorAndInsertingANewerBookmarkKeepsTheNextBoundary() throws Exception {
    var f = fixture();
    long tail = activity(f.stage());
    bookmark(f, tail, AT);
    long middle = activity(f.stage());
    bookmark(f, middle, AT);
    long anchor = activity(f.stage());
    bookmark(f, anchor, AT);
    var first = success(query(f, "size=1"));
    assertThat(ids(first)).containsExactly(anchor);
    assertThat(
            request(f.token(), "DELETE", "/api/v1/activities/" + anchor + "/save", null)
                .statusCode())
        .isEqualTo(200);
    bookmark(f, activity(f.stage()), AT.plusSeconds(1));
    var next = success(query(f, "size=50" + cursor(first.path("nextCursor").asText())));
    assertThat(ids(next)).containsExactly(middle, tail);
    assertLast(next);
  }

  @Test
  void unavailableBookmarkCanStillBeUnSavedAndRepeatedUnsaveIsSuccessful() throws Exception {
    var f = fixture();
    bookmark(f, f.activity(), AT);
    jdbc.update("UPDATE activities SET review_status='DRAFT' WHERE activity_id=?", f.activity());
    unavailable(success(query(f, "")).path("items").get(0), f.activity());
    for (int i = 0; i < 2; i++)
      assertThat(
              request(f.token(), "DELETE", "/api/v1/activities/" + f.activity() + "/save", null)
                  .statusCode())
          .isEqualTo(200);
    assertThat(success(query(f, "")).path("items").size()).isZero();
  }

  @Test
  void repeatedSaveKeepsSavedAtAndUnsaveThenSaveCreatesAFreshPosition() throws Exception {
    var f = fixture();
    var first =
        result(request(f.token(), "PUT", "/api/v1/activities/" + f.activity() + "/save", null));
    var again =
        result(request(f.token(), "PUT", "/api/v1/activities/" + f.activity() + "/save", null));
    assertThat(again.path("savedAt")).isEqualTo(first.path("savedAt"));
    assertThat(success(query(f, "")).path("items").get(0).path("savedAt"))
        .isEqualTo(first.path("savedAt"));
    assertThat(
            request(f.token(), "DELETE", "/api/v1/activities/" + f.activity() + "/save", null)
                .statusCode())
        .isEqualTo(200);
    var fresh = request(f.token(), "PUT", "/api/v1/activities/" + f.activity() + "/save", null);
    assertThat(fresh.statusCode()).isEqualTo(200);
    assertThat(Instant.parse(result(fresh).path("savedAt").asText()))
        .isAfterOrEqualTo(Instant.parse(first.path("savedAt").asText()));
  }

  @Test
  void higherStageBookmarksArePreviewableWithoutPlantingOrUnlockingATree() throws Exception {
    var f = fixture();
    long high = activity(stage(f.hobby(), 5));
    bookmark(f, high, AT);
    assertThat(success(query(f, "")).path("items").get(0).path("stageNumber").asInt()).isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM user_hobbies WHERE user_id=?", Integer.class, f.owner()))
        .isZero();
  }

  @Test
  void oneReadSnapshotDoesNotMixOldAvailabilityAndNewPrivateContent() throws Exception {
    var f = fixture();
    bookmark(f, f.activity(), AT);
    var reached = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              var value = invocation.callRealMethod();
              if (reached.getCount() > 0) {
                reached.countDown();
                assertThat(resume.await(8, TimeUnit.SECONDS)).isTrue();
              }
              return value;
            })
        .when(cursors)
        .decode(isNull(), eq(f.owner()), eq(0L));
    try (var executor = Executors.newSingleThreadExecutor()) {
      var response = executor.submit(() -> query(f, ""));
      try {
        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue();
        try (var writer = dataSource.getConnection()) {
          writer.setAutoCommit(false);
          try (var stmt =
              writer.prepareStatement(
                  "UPDATE activities SET activity_name='PRIVATE NEW TITLE',review_status='DRAFT' WHERE activity_id=?")) {
            stmt.setLong(1, f.activity());
            stmt.executeUpdate();
          }
          writer.commit();
        }
        resume.countDown();
        var old = success(response.get(12, TimeUnit.SECONDS)).path("items").get(0);
        assertThat(old.path("isAvailable").asBoolean()).isTrue();
        assertThat(old.path("title").asText()).isEqualTo("TEST ONLY saved33 activity");
        unavailable(success(query(f, "")).path("items").get(0), f.activity());
      } finally {
        resume.countDown();
      }
    }
  }

  @Test
  void deletingAnActivityRecordNeverUnsavesItsBookmark() throws Exception {
    var f = fixture();
    var save = request(f.token(), "PUT", "/api/v1/activities/" + f.activity() + "/save", null);
    assertThat(save.statusCode()).isEqualTo(200);
    long tree =
        jdbc.queryForObject(
            "INSERT INTO user_hobbies(user_id,hobby_id) VALUES(?,?) RETURNING user_hobby_id",
            Long.class,
            f.owner(),
            f.hobby());
    var started =
        request(
            f.token(),
            "POST",
            "/api/v1/user-hobbies/" + tree + "/activity-records",
            "{\"activityId\":" + f.activity() + "}");
    assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
    long record = result(started).path("recordId").asLong();
    var steps =
        jdbc.queryForList(
            "SELECT step_id FROM activity_record_steps WHERE record_id=? ORDER BY step_number",
            Long.class,
            record);
    assertThat(
            request(
                    f.token(),
                    "PATCH",
                    "/api/v1/activity-records/" + record + "/progress",
                    "{\"expectedVersion\":1,\"completedStepIds\":" + steps + "}")
                .statusCode())
        .isEqualTo(200);
    assertThat(
            request(
                    f.token(),
                    "PATCH",
                    "/api/v1/activity-records/" + record + "/complete",
                    "{\"expectedVersion\":2,\"status\":\"COMPLETED\",\"feedback\":{\"satisfactionScore\":4,\"perceivedDifficulty\":\"APPROPRIATE\"}}")
                .statusCode())
        .isEqualTo(200);
    var deleted = request(f.token(), "DELETE", "/api/v1/activity-records/" + record, null);
    assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(200);
    var item = success(query(f, "")).path("items").get(0);
    assertThat(item.path("isAvailable").asBoolean()).isTrue();
    assertThat(item.path("savedAt")).isEqualTo(result(save).path("savedAt"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_activities WHERE user_id=?", Integer.class, f.owner()))
        .isEqualTo(1);
  }

  @Test
  void openApiPreservesIntegerQueryTypesAndLimits() throws Exception {
    var response = request(null, "GET", "/v3/api-docs", null);
    assertThat(response.statusCode()).isEqualTo(200);
    var parameters =
        mapper
            .readTree(response.body())
            .path("paths")
            .path("/api/v1/users/me/saved-activities")
            .path("get")
            .path("parameters");
    int found = 0;
    for (var parameter : parameters) {
      if (List.of("hobbyId", "size").contains(parameter.path("name").asText())) {
        assertThat(parameter.path("schema").path("type").asText()).isEqualTo("integer");
        found++;
        if ("size".equals(parameter.path("name").asText())) {
          assertThat(parameter.path("schema").path("default").asInt()).isEqualTo(20);
          assertThat(parameter.path("schema").path("minimum").asInt()).isEqualTo(1);
          assertThat(parameter.path("schema").path("maximum").asInt()).isEqualTo(50);
        }
      }
    }
    assertThat(found).isEqualTo(2);
  }

  private Fixture fixture() throws Exception {
    socialId = "saved33-member-" + UUID.randomUUID();
    when(verifier.verify(any(), anyString())).thenReturn(socialId);
    var auth = result(login());
    long owner = auth.path("userId").asLong();
    createdUsers.add(owner);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", owner);
    long hobby = hobby();
    long stage = stage(hobby, 0);
    return new Fixture(owner, auth.path("accessToken").asText(), hobby, stage, activity(stage));
  }

  private long hobby() {
    return jdbc.queryForObject(
        "INSERT INTO hobbies(hobby_code,hobby_name,is_supported,indoor_outdoor,thumbnail_url) VALUES(?,'TEST ONLY saved33 hobby',true,'OUTDOOR','https://example.invalid/saved33-hobby.png') RETURNING hobby_id",
        Long.class,
        "saved33-test-" + UUID.randomUUID());
  }

  private long stage(long hobby, int number) {
    return jdbc.queryForObject(
        "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES(?,?,'TEST ONLY saved33') RETURNING hobby_stage_id",
        Long.class,
        hobby,
        number);
  }

  private long activity(long stage) {
    long id =
        jdbc.queryForObject(
            "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status,expected_total_minutes,estimated_cost_min,difficulty) VALUES(?,?,'TEST ONLY saved33 activity','PUBLISHED',42,100,'BEGINNER') RETURNING activity_id",
            Long.class,
            stage,
            "saved33-test-" + UUID.randomUUID());
    jdbc.update(
        "INSERT INTO activity_steps(activity_id,step_number,title,instruction,is_required) VALUES(?,1,'TEST ONLY step','TEST ONLY instruction',true)",
        id);
    return id;
  }

  private void bookmark(Fixture f, long activity, Instant at) {
    jdbc.update(
        "INSERT INTO saved_activities(user_id,activity_id,saved_at) VALUES(?,?,?)",
        f.owner(),
        activity,
        Timestamp.from(at));
  }

  private String path() {
    return "/api/v1/users/me/saved-activities";
  }

  private HttpResponse<String> query(Fixture f, String query) throws Exception {
    return request(f.token(), "GET", path() + (query.isEmpty() ? "" : "?" + query), null);
  }

  private HttpResponse<String> request(String token, String method, String path, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(15))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body != null) request.header("Content-Type", "application/json");
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String cursor(String value) {
    return value == null ? "" : "&cursor=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String code(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("code").asText();
  }

  private JsonNode success(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(code(response)).isEqualTo("SAVED200_3");
    return result(response);
  }

  private void bad(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    assertThat(code(response)).isEqualTo("COMMON400_1");
  }

  private List<Long> ids(JsonNode body) {
    var ids = new ArrayList<Long>();
    body.path("items").forEach(row -> ids.add(row.path("activityId").asLong()));
    return ids;
  }

  private void assertLast(JsonNode body) {
    assertThat(body.path("hasNext").asBoolean()).isFalse();
    assertThat(body.path("nextCursor").isNull()).isTrue();
  }

  private void unavailable(JsonNode item, long activity) {
    assertThat(item.propertyNames())
        .containsExactlyInAnyOrder("activityId", "savedAt", "isAvailable", "title");
    assertThat(item.path("activityId").asLong()).isEqualTo(activity);
    assertThat(item.path("isAvailable").asBoolean()).isFalse();
    assertThat(item.path("title").asText()).isEqualTo("이용할 수 없는 활동입니다");
  }

  private List<?> state(Fixture f) {
    return jdbc.queryForList(
        "SELECT to_jsonb(s)::text AS saved FROM saved_activities s WHERE user_id=? ORDER BY activity_id",
        f.owner());
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update(
        "DELETE FROM user_hobbies WHERE hobby_id IN(SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'saved33-test-%')");
    jdbc.update(
        "DELETE FROM activities WHERE hobby_stage_id IN(SELECT s.hobby_stage_id FROM hobby_stages s JOIN hobbies h USING(hobby_id) WHERE h.hobby_code LIKE 'saved33-test-%')");
    jdbc.update(
        "DELETE FROM hobby_stages WHERE hobby_id IN(SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'saved33-test-%')");
    jdbc.update("DELETE FROM hobbies WHERE hobby_code LIKE 'saved33-test-%'");
    super.cleanupSeededHobbies(userId);
  }

  private record Fixture(long owner, String token, long hobby, long stage, long activity) {}
}
