package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.auth.service.JwtTokenService;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
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
class StageActivityIntegrationTests extends IntegrationTestSupport {
  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  void returnsEveryFieldForAMemberWithoutATreeUsingTheSameTimeAndCostAsApi17() throws Exception {
    var member = signIn();
    long hobby = hobby(member.id(), true);
    long stage = stage(hobby, 0);
    long activity = activity(stage, "PUBLISHED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM user_hobbies WHERE user_id=?", Integer.class, member.id()))
        .isZero();

    var body = success(query(member.token(), stage, null, null));

    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder(
            "stageId", "hobbyId", "stageNumber", "items", "nextCursor", "hasNext");
    assertThat(body.path("stageId").asLong()).isEqualTo(stage);
    assertThat(body.path("hobbyId").asLong()).isEqualTo(hobby);
    assertThat(body.path("stageNumber").asInt()).isEqualTo(1);
    assertThat(body.path("items").size()).isEqualTo(1);
    var row = body.path("items").get(0);
    assertThat(row.propertyNames())
        .containsExactlyInAnyOrder(
            "activityId",
            "title",
            "thumbnailUrl",
            "durationMinutes",
            "estimatedCost",
            "difficulty",
            "indoorOutdoor",
            "isSaved");
    assertThat(row.path("activityId").asLong()).isEqualTo(activity);
    assertThat(row.path("title").asText()).isEqualTo("실제 활동 제목");
    assertThat(row.path("thumbnailUrl").asText()).isEqualTo("https://example.com/hobby.png");
    assertThat(row.path("durationMinutes").asInt()).isEqualTo(35);
    assertThat(row.path("estimatedCost").asInt()).isEqualTo(1200);
    assertThat(row.path("difficulty").asText()).isEqualTo("BEGINNER");
    assertThat(row.path("indoorOutdoor").asText()).isEqualTo("OUTDOOR");
    assertThat(row.path("isSaved").asBoolean()).isFalse();
    assertLast(body);
    var detail = get(member.token(), "/api/v1/hobbies/" + hobby);
    assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
    assertThat(row.path("durationMinutes")).isEqualTo(result(detail).path("minDurationMinutes"));
    assertThat(row.path("estimatedCost")).isEqualTo(result(detail).path("minCost"));
  }

  @Test
  void keepsNullableMetadataExplicitlyNullAndDoesNotInventZeroOrText() throws Exception {
    var member = signIn();
    long hobby = hobby(member.id(), true);
    long stage = stage(hobby, 0);
    long activity = activity(stage, "PUBLISHED");
    jdbc.update("UPDATE hobbies SET thumbnail_url=NULL WHERE hobby_id=?", hobby);
    jdbc.update(
        "UPDATE activities SET expected_total_minutes=NULL,estimated_cost_min=NULL,difficulty=NULL WHERE activity_id=?",
        activity);
    var row = success(query(member.token(), stage, null, null)).path("items").get(0);
    for (String field : List.of("thumbnailUrl", "durationMinutes", "estimatedCost", "difficulty")) {
      assertThat(row.has(field)).as(field).isTrue();
      assertThat(row.path(field).isNull()).as(field).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void mapsEveryStoredStageToTheExternalNumberWithoutChangingTheDatabase(int number)
      throws Exception {
    var member = signIn();
    long stage = stage(hobby(member.id(), true), number);
    activity(stage, "PUBLISHED");
    assertThat(success(query(member.token(), stage, null, null)).path("stageNumber").asInt())
        .isEqualTo(number + 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT stage_number FROM hobby_stages WHERE hobby_stage_id=?",
                Integer.class,
                stage))
        .isEqualTo(number);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DRAFT", "REVIEW", "REJECTED"})
  void returnsAnEmptyFinalPageWhenOnlyPrivateActivitiesExist(String status) throws Exception {
    var member = signIn();
    long stage = stage(hobby(member.id(), true), 0);
    activity(stage, status);
    var body = success(query(member.token(), stage, null, null));
    assertThat(body.path("items").isArray()).isTrue();
    assertThat(body.path("items").size()).isZero();
    assertLast(body);
  }

  @Test
  void returnsAnEmptyFinalPageWhenTheStageHasNoActivities() throws Exception {
    var member = signIn();
    long stage = stage(hobby(member.id(), true), 0);
    var body = success(query(member.token(), stage, null, null));
    assertThat(body.path("items").size()).isZero();
    assertLast(body);
  }

  @Test
  void previewsLockedContentWithoutChangingTheTreeOrGrantingActivityStart() throws Exception {
    var member = signIn();
    long hobby = hobby(member.id(), true);
    long stage = stage(hobby, 5);
    activity(stage, "PUBLISHED");
    long tree =
        jdbc.queryForObject(
            "INSERT INTO user_hobbies(user_id,hobby_id,current_stage_number) VALUES (?,?,0) RETURNING user_hobby_id",
            Long.class,
            member.id(),
            hobby);
    var before = jdbc.queryForMap("SELECT * FROM user_hobbies WHERE user_hobby_id=?", tree);
    assertThat(success(query(member.token(), stage, null, null)).path("items").size()).isEqualTo(1);
    assertThat(jdbc.queryForMap("SELECT * FROM user_hobbies WHERE user_hobby_id=?", tree))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_records WHERE user_hobby_id=?", Integer.class, tree))
        .isZero();
  }

  @Test
  void isolatesSavedFlagsAndNeverMutatesEitherUsersSavedRows() throws Exception {
    var owner = signIn();
    var other = otherUser();
    long stage = stage(hobby(owner.id(), true), 0);
    long mine = activity(stage, "PUBLISHED");
    long theirs = activity(stage, "PUBLISHED");
    long neither = activity(stage, "PUBLISHED");
    jdbc.update(
        "INSERT INTO saved_activities(user_id,activity_id) VALUES (?,?),(?,?)",
        owner.id(),
        mine,
        other.id(),
        theirs);
    var before =
        jdbc.queryForList(
            "SELECT * FROM saved_activities WHERE user_id IN (?,?) ORDER BY user_id,activity_id",
            owner.id(),
            other.id());
    var mineRows = success(query(owner.token(), stage, null, null)).path("items");
    var theirRows = success(query(other.token(), stage, null, null)).path("items");
    assertThat(ids(mineRows)).containsExactly(mine, theirs, neither);
    assertThat(mineRows.get(0).path("isSaved").asBoolean()).isTrue();
    assertThat(mineRows.get(1).path("isSaved").asBoolean()).isFalse();
    assertThat(mineRows.get(2).path("isSaved").asBoolean()).isFalse();
    assertThat(theirRows.get(0).path("isSaved").asBoolean()).isFalse();
    assertThat(theirRows.get(1).path("isSaved").asBoolean()).isTrue();
    assertThat(
            jdbc.queryForList(
                "SELECT * FROM saved_activities WHERE user_id IN (?,?) ORDER BY user_id,activity_id",
                owner.id(),
                other.id()))
        .isEqualTo(before);
  }

  @Test
  void reflectsApi31And32SaveUnsaveWithoutChangingTheirPolicies() throws Exception {
    var member = signIn();
    long stage = stage(hobby(member.id(), true), 0);
    long id = activity(stage, "PUBLISHED");
    assertThat(
            success(query(member.token(), stage, null, null))
                .path("items")
                .get(0)
                .path("isSaved")
                .asBoolean())
        .isFalse();
    var saved = save(member.token(), id, false);
    assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
    assertThat(
            success(query(member.token(), stage, null, null))
                .path("items")
                .get(0)
                .path("isSaved")
                .asBoolean())
        .isTrue();
    var savedAgain = save(member.token(), id, false);
    assertThat(result(savedAgain).path("savedAt")).isEqualTo(result(saved).path("savedAt"));
    assertThat(save(member.token(), id, true).statusCode()).isEqualTo(200);
    assertThat(
            success(query(member.token(), stage, null, null))
                .path("items")
                .get(0)
                .path("isSaved")
                .asBoolean())
        .isFalse();
  }

  @ParameterizedTest
  @CsvSource({"default, 20", "1, 1", "50, 50"})
  void honorsTheDefaultMinimumAndMaximumPageSizes(String size, int expected) throws Exception {
    var member = signIn();
    long stage = stage(hobby(member.id(), true), 0);
    for (int i = 0; i < 55; i++) activity(stage, "PUBLISHED");
    var body = success(query(member.token(), stage, null, size.equals("default") ? null : size));
    assertThat(body.path("items").size()).isEqualTo(expected);
    assertThat(body.path("hasNext").asBoolean()).isTrue();
    assertThat(body.path("nextCursor").asText()).isNotBlank();
  }

  @Test
  void walksStableFirstMiddleAndLastPagesWithoutDuplicatesOrOtherStageContent() throws Exception {
    var member = signIn();
    long hobby = hobby(member.id(), true);
    long stage = stage(hobby, 0);
    long foreign = stage(hobby, 1);
    List<Long> expected = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      activity(stage, "DRAFT");
      expected.add(activity(stage, "PUBLISHED"));
      activity(foreign, "PUBLISHED");
    }
    List<Long> actual = new ArrayList<>();
    String cursor = null;
    for (int page = 0; page < 3; page++) {
      var first = success(query(member.token(), stage, cursor, "2"));
      assertThat(success(query(member.token(), stage, cursor, "2"))).isEqualTo(first);
      actual.addAll(ids(first.path("items")));
      assertThat(first.path("items").size()).isEqualTo(page == 2 ? 1 : 2);
      if (page < 2) {
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        cursor = first.path("nextCursor").asText();
      } else assertLast(first);
    }
    assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
  }

  @Test
  void continuesAfterADeletedAnchorAndReflectsPublicationAndLaterInserts() throws Exception {
    var member = signIn();
    long stage = stage(hobby(member.id(), true), 0);
    long first = activity(stage, "PUBLISHED");
    long hidden = activity(stage, "PUBLISHED");
    long last = activity(stage, "PUBLISHED");
    var cursor = success(query(member.token(), stage, null, "1")).path("nextCursor").asText();
    jdbc.update("DELETE FROM activities WHERE activity_id=?", first);
    jdbc.update("UPDATE activities SET review_status='DRAFT' WHERE activity_id=?", hidden);
    long inserted = activity(stage, "PUBLISHED");
    var body = success(query(member.token(), stage, cursor, "50"));
    assertThat(ids(body.path("items"))).containsExactly(last, inserted);
    assertLast(body);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "51", "abc", "1.5", "", " ", "999999999999999999999"})
  void rejectsInvalidSizes(String size) throws Exception {
    var member = signIn();
    long stage = stage(hobby(member.id(), true), 0);
    assertError(query(member.token(), stage, null, size), 400, "COMMON400_1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "invalid", ".", "a.b.c", "e30.signature"})
  void rejectsMalformedCursors(String cursor) throws Exception {
    var member = signIn();
    long stage = stage(hobby(member.id(), true), 0);
    assertError(query(member.token(), stage, cursor, null), 400, "COMMON400_1");
  }

  @Test
  void rejectsForgedCrossStageCrossUserAndOversizedCursorsOverHttp() throws Exception {
    var member = signIn();
    var other = otherUser();
    long hobby = hobby(member.id(), true);
    long stage = stage(hobby, 0);
    long otherStage = stage(hobby, 1);
    activity(stage, "PUBLISHED");
    activity(stage, "PUBLISHED");
    String cursor = success(query(member.token(), stage, null, "1")).path("nextCursor").asText();
    assertError(query(member.token(), otherStage, cursor, null), 400, "COMMON400_1");
    assertError(query(other.token(), stage, cursor, null), 400, "COMMON400_1");
    String[] parts = cursor.split("\\.");
    String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
    String edited = payload.substring(0, payload.lastIndexOf(':') + 1) + Long.MAX_VALUE;
    String forged =
        Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(edited.getBytes(StandardCharsets.UTF_8))
            + "."
            + parts[1];
    assertError(query(member.token(), stage, forged, null), 400, "COMMON400_1");
    assertError(query(member.token(), stage, "a".repeat(257), null), 400, "COMMON400_1");
  }

  @Test
  void rejectsMissingStagesAndStagesInUnsupportedHobbiesWithTheSameError() throws Exception {
    var member = signIn();
    long stage = stage(hobby(member.id(), false), 0);
    activity(stage, "PUBLISHED");
    assertError(query(member.token(), stage, null, null), 404, "STAGE404_1");
    assertError(query(member.token(), Long.MAX_VALUE, null, null), 404, "STAGE404_1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
  void rejectsInvalidStageIds(String id) throws Exception {
    assertError(
        get(signIn().token(), "/api/v1/hobby-stages/" + id + "/activities"), 400, "COMMON400_1");
  }

  @Test
  void rejectsMissingForgedExpiredAndRevokedJwt() throws Exception {
    var member = signIn();
    long stage = stage(hobby(member.id(), true), 0);
    assertError(query(null, stage, null, null), 401, "AUTH401_2");
    assertError(query("invalid", stage, null, null), 401, "AUTH401_2");
    var issuer =
        new JwtTokenService(
            encoder, authProperties, Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));
    String sid = decoder.decode(member.token()).getClaimAsString("sid");
    assertError(
        query(
            issuer.issue(users.findById(member.id()).orElseThrow(), sid, "access"),
            stage,
            null,
            null),
        401,
        "AUTH401_2");
    redis.delete("auth:session:" + member.id());
    assertError(query(member.token(), stage, null, null), 401, "AUTH401_2");
  }

  @Test
  void rejectsIncompleteOnboarding() throws Exception {
    assertError(
        query(result(login()).path("accessToken").asText(), 1, null, null), 403, "AUTH403_2");
  }

  @ParameterizedTest
  @CsvSource({"SUSPENDED, AUTH403_1", "WITHDRAWN, AUTH403_3"})
  void rejectsInactiveMembers(String status, String expected) throws Exception {
    var member = signIn();
    long stage = stage(hobby(member.id(), true), 0);
    jdbc.update(
        """
        UPDATE users SET account_status=?, withdrawn_at=CASE WHEN ?='WITHDRAWN' THEN now() END,
          restorable_until=CASE WHEN ?='WITHDRAWN' THEN now()+INTERVAL '720 hours' END,
          status_before_withdrawal=CASE WHEN ?='WITHDRAWN' THEN 'ACTIVE' END WHERE user_id=?
        """,
        status,
        status,
        status,
        status,
        member.id());
    assertError(query(member.token(), stage, null, null), 403, expected);
  }

  /** Reuses the existing saved-row fixture to verify the API-33 PostgreSQL read path. */
  @Test
  void doesNotAddQueriesWhenThePageGrowsAndReadsSavedFlagsInThePageQuery() throws Exception {
    var member = signIn();
    long hobby = hobby(member.id(), true);
    long stage = stage(hobby, 0);
    for (int i = 0; i < 51; i++) {
      long id = activity(stage, "PUBLISHED");
      jdbc.update(
          "INSERT INTO saved_activities(user_id,activity_id) VALUES (?,?)", member.id(), id);
    }
    var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean enabled = stats.isStatisticsEnabled();
    stats.setStatisticsEnabled(true);
    try {
      long before = stats.getPrepareStatementCount();
      assertThat(success(query(member.token(), stage, null, "1")).path("items").size())
          .isEqualTo(1);
      long single = stats.getPrepareStatementCount() - before;
      before = stats.getPrepareStatementCount();
      var body = success(query(member.token(), stage, null, "50"));
      assertThat(body.path("items").size()).isEqualTo(50);
      for (var item : body.path("items")) assertThat(item.path("isSaved").asBoolean()).isTrue();
      assertThat(single).isPositive();
      assertThat(stats.getPrepareStatementCount() - before).isEqualTo(single);
    } finally {
      stats.setStatisticsEnabled(enabled);
    }
    assertSavedActivityPages(member, hobby);
  }

  /** Compares the API's two pages to the ordered bookmark rows without modifying the fixture. */
  private void assertSavedActivityPages(Member member, long hobby) throws Exception {
    var expected =
        jdbc.queryForList(
            "SELECT activity_id FROM saved_activities WHERE user_id=? ORDER BY saved_at DESC, activity_id DESC",
            Long.class,
            member.id());
    assertThat(expected).hasSize(51);
    String path = "/api/v1/users/me/saved-activities?hobbyId=";
    var firstResponse = get(member.token(), path + hobby + "&size=50");
    assertThat(firstResponse.statusCode()).as(firstResponse.body()).isEqualTo(200);
    var first = result(firstResponse);
    assertThat(ids(first.path("items"))).containsExactlyElementsOf(expected.subList(0, 50));
    assertThat(first.path("hasNext").asBoolean()).isTrue();
    var row = first.path("items").get(0);
    assertThat(row.path("hobbyId").asLong()).isEqualTo(hobby);
    assertThat(row.path("indoorOutdoor").asText()).isEqualTo("OUTDOOR");
    assertThat(row.path("thumbnailUrl").asText()).isEqualTo("https://example.com/hobby.png");
    assertThat(row.path("isAvailable").asBoolean()).isTrue();

    var nextResponse =
        get(
            member.token(),
            path
                + hobby
                + "&size=50&cursor="
                + URLEncoder.encode(first.path("nextCursor").asText(), StandardCharsets.UTF_8));
    assertThat(nextResponse.statusCode()).as(nextResponse.body()).isEqualTo(200);
    var next = result(nextResponse);
    assertThat(ids(next.path("items"))).containsExactly(expected.getLast());
    assertLast(next);

    var noMatch = get(member.token(), path + Long.MAX_VALUE);
    assertThat(noMatch.statusCode()).as(noMatch.body()).isEqualTo(200);
    assertThat(ids(result(noMatch).path("items"))).isEmpty();
  }

  private Member signIn() throws Exception {
    var response = login();
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var body = result(response);
    long id = body.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return new Member(id, body.path("accessToken").asText());
  }

  private Member otherUser() throws Exception {
    // A separate login issues a real JWT/session without replacing the first member's session.
    setup();
    return signIn();
  }

  private long hobby(long user, boolean supported) {
    return jdbc.queryForObject(
        "INSERT INTO hobbies(hobby_code,hobby_name,is_supported,thumbnail_url,indoor_outdoor) VALUES (?,'목록 테스트',?,'https://example.com/hobby.png','OUTDOOR') RETURNING hobby_id",
        Long.class,
        "profile-test-" + user + "-" + UUID.randomUUID().toString().substring(0, 12),
        supported);
  }

  private long stage(long hobby, int number) {
    return jdbc.queryForObject(
        "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,?,'실제 단계') RETURNING hobby_stage_id",
        Long.class,
        hobby,
        number);
  }

  private long activity(long stage, String status) {
    return jdbc.queryForObject(
        """
        INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status,
          expected_active_minutes,expected_total_minutes,estimated_cost_min,estimated_cost_max,difficulty)
        VALUES (?,?,'실제 활동 제목',?,10,35,1200,5000,'BEGINNER') RETURNING activity_id
        """,
        Long.class,
        stage,
        UUID.randomUUID().toString(),
        status);
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update(
        "DELETE FROM user_hobbies WHERE hobby_id IN (SELECT hobby_id FROM hobbies WHERE hobby_code LIKE ?)",
        "profile-test-" + userId + "-%");
    jdbc.update(
        "DELETE FROM activities WHERE hobby_stage_id IN (SELECT hs.hobby_stage_id FROM hobby_stages hs JOIN hobbies h USING (hobby_id) WHERE h.hobby_code LIKE ?)",
        "profile-test-" + userId + "-%");
    super.cleanupSeededHobbies(userId);
  }

  private HttpResponse<String> query(String token, long stage, String cursor, String size)
      throws Exception {
    List<String> params = new ArrayList<>();
    if (cursor != null) params.add("cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    if (size != null) params.add("size=" + URLEncoder.encode(size, StandardCharsets.UTF_8));
    return get(
        token,
        "/api/v1/hobby-stages/"
            + stage
            + "/activities"
            + (params.isEmpty() ? "" : "?" + String.join("&", params)));
  }

  private HttpResponse<String> get(String token, String path) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> save(String token, long id, boolean delete) throws Exception {
    var builder =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/activities/" + id + "/save"))
            .header("Authorization", "Bearer " + token);
    if (delete) builder.DELETE();
    else builder.PUT(HttpRequest.BodyPublishers.noBody());
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode success(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    var body = mapper.readTree(response.body());
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder("isSuccess", "code", "message", "result");
    assertThat(body.path("isSuccess").asBoolean()).isTrue();
    assertThat(body.path("code").asText()).isEqualTo("ACTIVITY200_2");
    assertThat(body.path("message").asText()).isEqualTo("단계별 활동 목록을 조회했습니다.");
    return body.path("result");
  }

  private List<Long> ids(JsonNode rows) {
    List<Long> ids = new ArrayList<>();
    for (var row : rows) ids.add(row.path("activityId").asLong());
    return ids;
  }

  private void assertLast(JsonNode body) {
    assertThat(body.has("nextCursor")).isTrue();
    assertThat(body.path("nextCursor").isNull()).isTrue();
    assertThat(body.path("hasNext").asBoolean()).isFalse();
  }

  private void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    var root = mapper.readTree(response.body());
    assertThat(root.path("isSuccess").asBoolean()).isFalse();
    assertThat(root.path("code").asText()).isEqualTo(code);
    assertThat(root.path("result").isNull()).isTrue();
  }

  private record Member(long id, String token) {}
}
