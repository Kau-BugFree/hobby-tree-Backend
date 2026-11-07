package com.hobbytree.hobbytree.domain.tree;

import static org.assertj.core.api.Assertions.assertThat;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.service.JwtTokenService;
import com.hobbytree.hobbytree.support.GrowthLedgerFixtures;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
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
@TestPropertySource(
    properties = {
      "spring.jpa.open-in-view=false",
      "spring.jpa.properties.hibernate.generate_statistics=true"
    })
class UserHobbyIntegrationTests extends IntegrationTestSupport {
  private static final String PATH = "/api/v1/user-hobbies";
  private static final String PLANTED_AT = "2026-10-25T01:00:00.123456Z";

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  void returnsEmptyListForAuthenticatedOwnerWithoutTrees() throws Exception {
    var owner = signIn();

    var response = query(owner.token());

    var page = assertSuccess(response);
    assertThat(page.path("items").size()).isZero();
    assertThat(page.path("nextCursor").isNull()).isTrue();
    assertThat(page.path("hasNext").asBoolean()).isFalse();
    assertThat(page.path("occupiedTreeCount").asLong()).isZero();
    assertThat(page.path("maxTreeCount").asInt()).isEqualTo(5);
  }

  /** Listing exposes the owner's external stage and preserves the stored stage's name. */
  @Test
  void loadsHobbiesAndReturnsOnlyTreesOwnedByTheJwtPrincipal() throws Exception {
    var owner = signIn();
    long other =
        jdbc.queryForObject(
            "INSERT INTO users(provider,provider_user_id,account_status)"
                + " VALUES ('KAKAO',?,'ACTIVE') RETURNING user_id",
            Long.class,
            "tree-list-test-" + UUID.randomUUID());
    createdUsers.add(other);
    long firstHobby = hobby(owner.id(), "요리");
    long secondHobby = hobby(owner.id(), "독서");
    stage(firstHobby, 0, "씨앗");
    stage(secondHobby, 0, "독서 씨앗");
    long first = plant(owner.id(), firstHobby, "ACTIVE", 0, PLANTED_AT);
    long second = plant(owner.id(), secondHobby, "ACTIVE", 0, PLANTED_AT);
    plant(other, firstHobby, "ACTIVE", 0, PLANTED_AT);
    jdbc.update(
        "UPDATE hobbies SET thumbnail_url=? WHERE hobby_id=?",
        "https://example.com/h.png",
        firstHobby);

    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    var page = assertSuccess(query(owner.token(), "?userId=" + other));
    assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(6);
    assertThat(ids(page)).containsExactly(second, first);
    assertThat(page.path("occupiedTreeCount").asLong()).isEqualTo(2);
    var tree = page.path("items").get(1);
    assertThat(tree.propertyNames())
        .containsExactlyInAnyOrder(
            "userHobbyId",
            "hobbyId",
            "hobbyName",
            "thumbnailUrl",
            "treeStatus",
            "currentStageNumber",
            "currentStageName",
            "distinctCompletedActivityCount",
            "requiredCountForNextStage",
            "remainingCountForNextStage",
            "plantedAt");
    assertThat(tree.path("hobbyId").asLong()).isEqualTo(firstHobby);
    assertThat(tree.path("hobbyName").asText()).isEqualTo("요리");
    assertThat(tree.path("thumbnailUrl").asText()).isEqualTo("https://example.com/h.png");
    assertThat(tree.path("treeStatus").asText()).isEqualTo("ACTIVE");
    assertThat(tree.path("currentStageNumber").asInt()).isEqualTo(1);
    assertThat(tree.path("currentStageName").asText()).isEqualTo("씨앗");
    assertThat(tree.path("distinctCompletedActivityCount").asLong()).isZero();
    assertThat(tree.path("requiredCountForNextStage").asInt()).isEqualTo(3);
    assertThat(tree.path("remainingCountForNextStage").asLong()).isEqualTo(3);
    assertThat(Instant.parse(tree.path("plantedAt").asText())).isEqualTo(Instant.parse(PLANTED_AT));
  }

  @Test
  void filtersByEachStatusWithoutChangingTheOccupiedCount() throws Exception {
    var owner = signIn();
    long active = plant(owner.id(), hobby(owner.id(), "활성"), "ACTIVE", 0, PLANTED_AT);
    long paused = plant(owner.id(), hobby(owner.id(), "일시정지"), "PAUSED", 0, PLANTED_AT);
    long ended = plant(owner.id(), hobby(owner.id(), "종료"), "ENDED", 0, PLANTED_AT);

    var unfiltered = assertSuccess(query(owner.token()));
    assertThat(ids(unfiltered)).containsExactly(ended, paused, active);
    assertThat(unfiltered.path("occupiedTreeCount").asLong()).isEqualTo(2);
    assertThat(unfiltered.path("items").get(0).path("currentStageName").isNull()).isTrue();

    var activePage = assertSuccess(query(owner.token(), "?status=ACTIVE&size=50"));
    var pausedPage = assertSuccess(query(owner.token(), "?status=PAUSED"));
    var endedPage = assertSuccess(query(owner.token(), "?status=ENDED"));
    assertThat(ids(activePage)).containsExactly(active);
    assertThat(ids(pausedPage)).containsExactly(paused);
    assertThat(ids(endedPage)).containsExactly(ended);
    for (var page : List.of(activePage, pausedPage, endedPage)) {
      assertThat(page.path("occupiedTreeCount").asLong()).isEqualTo(2);
      assertThat(page.path("hasNext").asBoolean()).isFalse();
    }
  }

  @Test
  void walksEqualAndAdjacentMicrosecondTimestampsInDescendingKeysetOrder() throws Exception {
    var owner = signIn();
    long first = plant(owner.id(), hobby(owner.id(), "첫째"), "ACTIVE", 0, PLANTED_AT);
    long second = plant(owner.id(), hobby(owner.id(), "둘째"), "ACTIVE", 0, PLANTED_AT);
    long older =
        plant(owner.id(), hobby(owner.id(), "이전"), "ACTIVE", 0, "2026-10-25T01:00:00.123455Z");
    plant(owner.id(), hobby(owner.id(), "제외"), "ENDED", 0, PLANTED_AT);
    String query = "?status=ACTIVE&size=1";

    var page1 = assertSuccess(query(owner.token(), query));
    assertThat(ids(page1)).containsExactly(second);
    assertThat(page1.path("hasNext").asBoolean()).isTrue();
    assertThat(page1.path("nextCursor").asText()).isNotBlank();
    var page2 =
        assertSuccess(query(owner.token(), query + "&cursor=" + page1.path("nextCursor").asText()));
    assertThat(ids(page2)).containsExactly(first);
    assertThat(page2.path("hasNext").asBoolean()).isTrue();
    var page3 =
        assertSuccess(query(owner.token(), query + "&cursor=" + page2.path("nextCursor").asText()));
    assertThat(ids(page3)).containsExactly(older);
    assertThat(page3.path("hasNext").asBoolean()).isFalse();
    assertThat(page3.path("nextCursor").isNull()).isTrue();
    assertThat(page3.path("occupiedTreeCount").asLong()).isEqualTo(3);
  }

  /**
   * Display conversion must leave cumulative credit, current-stage targets, and DB stage intact.
   */
  @Test
  void usesCumulativeCreditAndCurrentStageProgressWithoutChangingStoredStage() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), "악기");
    jdbc.update("UPDATE hobbies SET activities_per_stage=4 WHERE hobby_id=?", hobbyId);
    long previousStage = stage(hobbyId, 0, "이전 단계");
    long currentStage = stage(hobbyId, 1, "연습 중");
    long treeId = plant(owner.id(), hobbyId, "ACTIVE", 1, PLANTED_AT);
    record(treeId, previousStage, "COMPLETED", 0, 3);
    record(treeId, currentStage, "COMPLETED", 1, 1);
    record(treeId, currentStage, "COMPLETED", null, 1);
    record(treeId, currentStage, "PARTIAL", null, 1);

    var tree = assertSuccess(query(owner.token())).path("items").get(0);

    assertThat(tree.path("currentStageNumber").asInt()).isEqualTo(2);
    assertThat(tree.path("currentStageName").asText()).isEqualTo("연습 중");
    assertThat(tree.path("distinctCompletedActivityCount").asLong()).isEqualTo(2);
    assertThat(tree.path("requiredCountForNextStage").asInt()).isEqualTo(4);
    assertThat(tree.path("remainingCountForNextStage").asLong()).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT current_stage_number FROM user_hobbies WHERE user_hobby_id=?",
                Integer.class,
                treeId))
        .isEqualTo(1);
  }

  /** The stored maximum displays six while retaining its null next target and zero remaining. */
  @Test
  void reportsNoNextStageTargetAtTheSchemaMaximum() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), "최고 단계 취미");
    long previousStage = stage(hobbyId, 4, "이전 단계");
    stage(hobbyId, 5, "최고 단계");
    long treeId = plant(owner.id(), hobbyId, "ENDED", 5, PLANTED_AT);
    record(treeId, previousStage, "COMPLETED", 4, 1);

    var page = assertSuccess(query(owner.token()));
    var tree = page.path("items").get(0);

    assertThat(page.path("occupiedTreeCount").asLong()).isZero();
    assertThat(tree.path("currentStageNumber").asInt()).isEqualTo(6);
    assertThat(tree.path("currentStageName").asText()).isEqualTo("최고 단계");
    assertThat(tree.path("distinctCompletedActivityCount").asLong()).isEqualTo(1);
    assertThat(tree.path("requiredCountForNextStage").isNull()).isTrue();
    assertThat(tree.path("remainingCountForNextStage").asLong()).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "?status=active", "?status=", "?status=DELETED", "?size=0", "?size=51",
        "?size=word", "?size=", "?cursor=***", "?cursor=", "?cursor=YWJj"
      })
  void rejectsInvalidQueryParameters(String invalidQuery) throws Exception {
    var owner = signIn();
    var response = query(owner.token(), invalidQuery);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("COMMON400_1");
  }

  @Test
  void rejectsPendingOnboardingWithValidJwt() throws Exception {
    var response = login();
    assertThat(response.statusCode()).isEqualTo(200);
    assertAuthFailure(
        query(result(response).path("accessToken").asText()), AuthErrorCode.ONBOARDING_INCOMPLETE);
  }

  @Test
  void rejectsMissingAndInvalidJwt() throws Exception {
    assertAuthFailure(query(null), AuthErrorCode.ACCESS_TOKEN);
    assertAuthFailure(query("not-a-jwt"), AuthErrorCode.ACCESS_TOKEN);
  }

  @Test
  void rejectsRevokedJwt() throws Exception {
    var owner = signIn();
    redis.delete("auth:session:" + owner.id());

    assertAuthFailure(query(owner.token()), AuthErrorCode.ACCESS_TOKEN);
  }

  @Test
  void rejectsExpiredJwtForACurrentSession() throws Exception {
    var owner = signIn();
    var expiredIssuer =
        new JwtTokenService(
            encoder, authProperties, Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));
    String sid = decoder.decode(owner.token()).getClaimAsString("sid");
    String expired = expiredIssuer.issue(users.findById(owner.id()).orElseThrow(), sid, "access");

    assertAuthFailure(query(expired), AuthErrorCode.ACCESS_TOKEN);
  }

  @ParameterizedTest
  @CsvSource({"SUSPENDED, SUSPENDED", "WITHDRAWN, WITHDRAWN_ACCESS"})
  void rejectsIneligibleAccountWithPreviouslyValidJwt(String status, AuthErrorCode expected)
      throws Exception {
    var owner = signIn();
    jdbc.update(
        """
        UPDATE users
        SET account_status = target.status,
            withdrawn_at = CASE WHEN target.status = 'WITHDRAWN' THEN now() END,
            restorable_until = CASE WHEN target.status = 'WITHDRAWN' THEN now() + INTERVAL '720 hours' END,
            status_before_withdrawal = CASE WHEN target.status = 'WITHDRAWN' THEN 'ACTIVE' END
        FROM (VALUES (CAST(? AS varchar))) AS target(status)
        WHERE user_id = ?
        """,
        status,
        owner.id());
    assertAuthFailure(query(owner.token()), expected);
  }

  private SignedInUser signIn() throws Exception {
    var response = login();
    assertThat(response.statusCode()).isEqualTo(200);
    var result = result(response);
    long userId = result.path("userId").asLong();
    createdUsers.add(userId);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", userId);
    return new SignedInUser(userId, result.path("accessToken").asText());
  }

  private long hobby(long ownerId, String name) {
    // Use IntegrationTestSupport's fixture naming convention for cleanup.
    return jdbc.queryForObject(
        "INSERT INTO hobbies(hobby_code,hobby_name) VALUES (?,?) RETURNING hobby_id",
        Long.class,
        "profile-test-" + ownerId + "-" + UUID.randomUUID().toString().substring(0, 12),
        name);
  }

  private long plant(long userId, long hobbyId, String status, int stage, String plantedAt) {
    return jdbc.queryForObject(
        """
        INSERT INTO user_hobbies(user_id,hobby_id,tree_status,current_stage_number,planted_at)
        VALUES (?,?,?,?,?::timestamptz) RETURNING user_hobby_id
        """,
        Long.class,
        userId,
        hobbyId,
        status,
        stage,
        plantedAt);
  }

  private long stage(long hobbyId, int stageNumber, String name) {
    return jdbc.queryForObject(
        "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,?,?) RETURNING hobby_stage_id",
        Long.class,
        hobbyId,
        stageNumber,
        name);
  }

  private void record(long treeId, long stageId, String status, Integer creditStage, int attempts) {
    long activityId =
        jdbc.queryForObject(
            "INSERT INTO activities(hobby_stage_id,activity_code,activity_name) VALUES (?,?,'Test activity') RETURNING activity_id",
            Long.class,
            stageId,
            UUID.randomUUID().toString());
    jdbc.update(
        """
        INSERT INTO activity_records
          (user_hobby_id,activity_id,activity_status,attempt_count,growth_counted,growth_stage_number)
        VALUES (?,?,?,?,?,?)
        """,
        treeId,
        activityId,
        status,
        attempts,
        creditStage != null,
        creditStage);
    GrowthLedgerFixtures.copyCreditIfCounted(jdbc, treeId, activityId);
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    // Shared fixtures may be planted by both accounts. Remove fixture trees before hobbies.
    jdbc.update(
        "DELETE FROM user_hobbies WHERE hobby_id IN"
            + " (SELECT hobby_id FROM hobbies WHERE hobby_code LIKE ?)",
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

  private HttpResponse<String> query(String token) throws Exception {
    return query(token, "");
  }

  private HttpResponse<String> query(String token, String query) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + query))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void assertAuthFailure(HttpResponse<String> response, AuthErrorCode expected) {
    assertThat(response.statusCode()).isEqualTo(expected.getStatus().value());
    assertThat(mapper.readTree(response.body()).path("code").asText())
        .isEqualTo(expected.getCode());
  }

  private JsonNode assertSuccess(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var envelope = mapper.readTree(response.body());
    assertThat(envelope.propertyNames())
        .containsExactlyInAnyOrder("isSuccess", "code", "message", "result");
    assertThat(envelope.path("isSuccess").asBoolean()).isTrue();
    assertThat(envelope.path("code").asText()).isEqualTo("TREE200_1");
    assertThat(envelope.path("message").asText()).isEqualTo("내 취미 나무 목록을 조회했습니다.");
    var page = envelope.path("result");
    assertThat(page.propertyNames())
        .containsExactlyInAnyOrder(
            "items", "nextCursor", "hasNext", "occupiedTreeCount", "maxTreeCount");
    return page;
  }

  private List<Long> ids(JsonNode page) {
    return StreamSupport.stream(page.path("items").spliterator(), false)
        .map(row -> row.path("userHobbyId").asLong())
        .toList();
  }

  private record SignedInUser(long id, String token) {}
}
