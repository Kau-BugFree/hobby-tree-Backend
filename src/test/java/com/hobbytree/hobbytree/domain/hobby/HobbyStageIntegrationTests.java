package com.hobbytree.hobbytree.domain.hobby;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.auth.service.JwtTokenService;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
class HobbyStageIntegrationTests extends IntegrationTestSupport {
  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  void returnsEveryContractFieldInStageOrderForAMemberWithoutATree() throws Exception {
    var member = signIn();
    long hobbyId = hobby(member.id(), 4, true);
    long[] ids = new long[6];
    // Reverse insertion proves stage ordering is independent of ID ordering.
    for (int n = 5; n >= 0; n--)
      ids[n] = stage(hobbyId, n, "실제 단계 " + n, n == 2 ? null : "실제 설명 " + n);
    activity(ids[0], "PUBLISHED");
    activity(ids[0], "PUBLISHED");
    activity(ids[0], "DRAFT");
    activity(ids[0], "REVIEW");
    activity(ids[0], "REJECTED");
    activity(ids[5], "PUBLISHED");
    activity(stage(hobby(member.id(), 3, true), 0, "다른 취미", null), "PUBLISHED");
    assertThat(treeCount(member.id())).isZero();

    var response = success(query(member.token(), Long.toString(hobbyId)));

    assertThat(response.propertyNames())
        .containsExactlyInAnyOrder("hobbyId", "hobbyName", "stages");
    assertThat(response.path("hobbyId").asLong()).isEqualTo(hobbyId);
    assertThat(response.path("hobbyName").asText()).isEqualTo("단계 조회 테스트");
    var rows = response.path("stages");
    assertThat(rows.size()).isEqualTo(6);
    for (int n = 0; n <= 5; n++) {
      var row = rows.get(n);
      assertThat(row.propertyNames())
          .containsExactlyInAnyOrder(
              "stageId",
              "stageNumber",
              "name",
              "description",
              "activityCount",
              "unlockAtDistinctCompletedCount");
      assertThat(row.path("stageId").asLong()).isEqualTo(ids[n]);
      assertThat(row.path("stageNumber").asInt()).isEqualTo(n + 1);
      assertThat(row.path("name").asText()).isEqualTo("실제 단계 " + n);
      if (n == 2) assertThat(row.path("description").isNull()).isTrue();
      else assertThat(row.path("description").asText()).isEqualTo("실제 설명 " + n);
      assertThat(row.path("activityCount").asLong()).isEqualTo(n == 0 ? 2 : n == 5 ? 1 : 0);
      assertThat(row.path("unlockAtDistinctCompletedCount").asLong()).isEqualTo((long) n * 4);
    }
    assertThat(treeCount(member.id())).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT stage_number FROM hobby_stages WHERE hobby_id=? ORDER BY stage_number",
                Integer.class,
                hobbyId))
        .containsExactly(0, 1, 2, 3, 4, 5);
  }

  @ParameterizedTest
  @CsvSource({"PUBLISHED, 1", "DRAFT, 0", "REVIEW, 0", "REJECTED, 0"})
  void countsOnlyPublishedActivitiesAndRetainsStagesWithOnlyPrivateActivities(
      String status, int count) throws Exception {
    var member = signIn();
    long hobbyId = hobby(member.id(), 3, true);
    activity(stage(hobbyId, 0, "씨앗", null), status);

    var stages = success(query(member.token(), Long.toString(hobbyId))).path("stages");

    assertThat(stages.size()).isEqualTo(1);
    assertThat(stages.get(0).path("activityCount").asLong()).isEqualTo(count);
  }

  @Test
  void returnsAnEmptyArrayForAPublicHobbyWithoutStages() throws Exception {
    var member = signIn();
    long hobbyId = hobby(member.id(), 3, true);
    var stages = success(query(member.token(), Long.toString(hobbyId))).path("stages");
    assertThat(stages.isArray()).isTrue();
    assertThat(stages.size()).isZero();
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3, 4, 32767})
  void usesEachHobbysRealGrowthRuleAndStoredStageNumbersForSparseStages(int required)
      throws Exception {
    var member = signIn();
    long hobbyId = hobby(member.id(), required, true);
    stage(hobbyId, 5, "세계수", null);
    stage(hobbyId, 0, "씨앗", null);
    stage(hobbyId, 3, "나무", null);

    var rows = success(query(member.token(), Long.toString(hobbyId))).path("stages");

    assertThat(rows.size()).isEqualTo(3);
    for (int i = 0; i < 3; i++) {
      int stored = new int[] {0, 3, 5}[i];
      assertThat(rows.get(i).path("stageNumber").asInt()).isEqualTo(stored + 1);
      assertThat(rows.get(i).path("unlockAtDistinctCompletedCount").asLong())
          .isEqualTo((long) stored * required);
      assertThat(rows.get(i).path("activityCount").asLong()).isZero();
    }
  }

  @Test
  void agreesWithApi22StageNumbersThresholdsAndPublishedActivityIdsAfterPlanting()
      throws Exception {
    var member = signIn();
    long hobbyId = hobby(member.id(), 4, true);
    for (int number : new int[] {0, 2, 5}) {
      long stageId = stage(hobbyId, number, "단계 " + number, "설명");
      activity(stageId, "PUBLISHED");
      activity(stageId, "PUBLISHED");
      activity(stageId, "DRAFT");
    }
    var catalog = success(query(member.token(), Long.toString(hobbyId)));
    var planted =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/user-hobbies"))
                .header("Authorization", "Bearer " + member.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"hobbyId\":" + hobbyId + "}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(planted.statusCode()).as(planted.body()).isEqualTo(201);
    long treeId = result(planted).path("userHobbyId").asLong();
    var roadmap = get(member.token(), "/api/v1/user-hobbies/" + treeId + "/roadmap");
    assertThat(roadmap.statusCode()).as(roadmap.body()).isEqualTo(200);
    var stages = result(roadmap).path("stages");
    assertThat(stages.size()).isEqualTo(catalog.path("stages").size());
    for (int i = 0; i < stages.size(); i++) {
      var expected = stages.get(i);
      var actual = catalog.path("stages").get(i);
      for (String field :
          new String[] {"stageId", "stageNumber", "name", "unlockAtDistinctCompletedCount"})
        assertThat(actual.path(field)).as(field).isEqualTo(expected.path(field));
      assertThat(actual.path("activityCount").asLong())
          .isEqualTo(expected.path("representativeActivityIds").size());
    }
  }

  /** Measures real SQL: adding five stages and their activities must not add per-stage queries. */
  @Test
  void keepsTheSqlQueryCountConstantWhenTheCatalogGrowsFromOneToSixStages() throws Exception {
    var member = signIn();
    long hobbyId = hobby(member.id(), 3, true);
    activity(stage(hobbyId, 0, "씨앗", null), "PUBLISHED");
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean enabledBefore = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    try {
      long before = statistics.getPrepareStatementCount();
      assertThat(success(query(member.token(), Long.toString(hobbyId))).path("stages").size())
          .isEqualTo(1);
      long oneStage = statistics.getPrepareStatementCount() - before;
      for (int n = 1; n <= 5; n++) activity(stage(hobbyId, n, "단계 " + n, "설명"), "PUBLISHED");
      before = statistics.getPrepareStatementCount();
      assertThat(success(query(member.token(), Long.toString(hobbyId))).path("stages").size())
          .isEqualTo(6);
      long sixStages = statistics.getPrepareStatementCount() - before;
      assertThat(oneStage).isPositive();
      assertThat(sixStages).isEqualTo(oneStage);
    } finally {
      statistics.setStatisticsEnabled(enabledBefore);
    }
  }

  @Test
  void returnsTheSameNotFoundForMissingAndUnsupportedHobbies() throws Exception {
    var member = signIn();
    long hidden = hobby(member.id(), 3, false);
    activity(stage(hidden, 0, "비공개 취미의 단계", "비공개"), "PUBLISHED");
    assertError(query(member.token(), Long.toString(hidden)), 404, "HOBBY404_1");
    assertError(query(member.token(), Long.toString(Long.MAX_VALUE)), 404, "HOBBY404_1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
  void rejectsInvalidHobbyIds(String id) throws Exception {
    assertError(query(signIn().token(), id), 400, "COMMON400_1");
  }

  @Test
  void rejectsMissingForgedExpiredAndRevokedJwt() throws Exception {
    var member = signIn();
    String id = Long.toString(hobby(member.id(), 3, true));
    assertError(query(null, id), 401, "AUTH401_2");
    assertError(query("invalid", id), 401, "AUTH401_2");
    var issuer =
        new JwtTokenService(
            encoder, authProperties, Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));
    String sid = decoder.decode(member.token()).getClaimAsString("sid");
    assertError(
        query(issuer.issue(users.findById(member.id()).orElseThrow(), sid, "access"), id),
        401,
        "AUTH401_2");
    redis.delete("auth:session:" + member.id());
    assertError(query(member.token(), id), 401, "AUTH401_2");
  }

  @Test
  void rejectsIncompleteOnboarding() throws Exception {
    assertError(query(result(login()).path("accessToken").asText(), "1"), 403, "AUTH403_2");
  }

  @ParameterizedTest
  @CsvSource({"SUSPENDED, AUTH403_1", "WITHDRAWN, AUTH403_3"})
  void rejectsInactiveMembers(String status, String expected) throws Exception {
    var member = signIn();
    long hobbyId = hobby(member.id(), 3, true);
    jdbc.update(
        """
        UPDATE users SET account_status=?,
          withdrawn_at=CASE WHEN ?='WITHDRAWN' THEN now() END,
          restorable_until=CASE WHEN ?='WITHDRAWN' THEN now()+INTERVAL '720 hours' END,
          status_before_withdrawal=CASE WHEN ?='WITHDRAWN' THEN 'ACTIVE' END
        WHERE user_id=?
        """,
        status,
        status,
        status,
        status,
        member.id());
    assertError(query(member.token(), Long.toString(hobbyId)), 403, expected);
  }

  private SignedInUser signIn() throws Exception {
    var response = login();
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var signedIn = result(response);
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return new SignedInUser(id, signedIn.path("accessToken").asText());
  }

  private long treeCount(long userId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM user_hobbies WHERE user_id=?", Long.class, userId);
  }

  private long hobby(long userId, int required, boolean supported) {
    return jdbc.queryForObject(
        "INSERT INTO hobbies(hobby_code,hobby_name,activities_per_stage,is_supported) VALUES (?,'단계 조회 테스트',?,?) RETURNING hobby_id",
        Long.class,
        "profile-test-" + userId + "-" + UUID.randomUUID().toString().substring(0, 12),
        required,
        supported);
  }

  private long stage(long hobbyId, int number, String name, String description) {
    return jdbc.queryForObject(
        "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name,stage_description) VALUES (?,?,?,?) RETURNING hobby_stage_id",
        Long.class,
        hobbyId,
        number,
        name,
        description);
  }

  private void activity(long stageId, String status) {
    jdbc.update(
        "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status) VALUES (?,?,'단계 활동',?)",
        stageId,
        UUID.randomUUID().toString(),
        status);
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update(
        "DELETE FROM user_hobbies WHERE hobby_id IN (SELECT hobby_id FROM hobbies WHERE hobby_code LIKE ?)",
        "profile-test-" + userId + "-%");
    jdbc.update(
        """
        DELETE FROM activities WHERE hobby_stage_id IN
          (SELECT hs.hobby_stage_id FROM hobby_stages hs JOIN hobbies h USING (hobby_id)
           WHERE h.hobby_code LIKE ?)
        """,
        "profile-test-" + userId + "-%");
    super.cleanupSeededHobbies(userId);
  }

  private HttpResponse<String> query(String token, String id) throws Exception {
    return get(token, "/api/v1/hobbies/" + id + "/stages");
  }

  private HttpResponse<String> get(String token, String path) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode success(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    var root = mapper.readTree(response.body());
    assertThat(root.propertyNames())
        .containsExactlyInAnyOrder("isSuccess", "code", "message", "result");
    assertThat(root.path("isSuccess").asBoolean()).isTrue();
    assertThat(root.path("code").asText()).isEqualTo("ACTIVITY200_1");
    assertThat(root.path("message").asText()).isEqualTo("취미 단계 목록을 조회했습니다.");
    return root.path("result");
  }

  private void assertError(HttpResponse<String> response, int status, String expected) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    var root = mapper.readTree(response.body());
    assertThat(root.path("isSuccess").asBoolean()).isFalse();
    assertThat(root.path("code").asText()).isEqualTo(expected);
    assertThat(root.path("result").isNull()).isTrue();
  }

  private record SignedInUser(long id, String token) {}
}
