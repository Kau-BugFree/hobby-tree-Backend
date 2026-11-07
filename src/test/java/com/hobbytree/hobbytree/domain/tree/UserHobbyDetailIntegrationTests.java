package com.hobbytree.hobbytree.domain.tree;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.auth.service.JwtTokenService;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class UserHobbyDetailIntegrationTests extends IntegrationTestSupport {
  private static final String PATH = "/api/v1/user-hobbies";
  private static final String UPDATED = "2026-11-01T02:00:00Z";
  private static final List<String> COMMON_FIELDS =
      List.of(
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

  @Test
  void returnsEveryDetailFieldForATreePlantedByApi19AtStageZero() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), 4);
    stage(hobbyId, 0, "씨앗");
    stage(hobbyId, 1, "새싹");
    var planted =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .header("Authorization", "Bearer " + owner.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"hobbyId\":" + hobbyId + "}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(planted.statusCode()).isEqualTo(201);
    assertThat(mapper.readTree(planted.body()).path("code").asText()).isEqualTo("TREE201_1");
    long treeId = result(planted).path("userHobbyId").asLong();

    var detail = success(query(owner.token(), Long.toString(treeId)));

    assertThat(detail.propertyNames())
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
            "plantedAt",
            "totalStages",
            "isMaxStage",
            "currentStageCompletedCount",
            "currentStageRequiredCount",
            "currentActivityRecord",
            "lastActivityAt");
    assertThat(detail.path("hobbyId").asLong()).isEqualTo(hobbyId);
    assertThat(detail.path("hobbyName").asText()).isEqualTo("상세 테스트");
    assertThat(detail.path("thumbnailUrl").asText()).isEqualTo("https://example.com/tree.png");
    assertThat(detail.path("currentStageNumber").asInt()).isZero();
    assertThat(detail.path("currentStageName").asText()).isEqualTo("씨앗");
    assertThat(detail.path("distinctCompletedActivityCount").asLong()).isZero();
    assertThat(detail.path("currentStageCompletedCount").asLong()).isZero();
    assertThat(detail.path("requiredCountForNextStage").asInt()).isEqualTo(4);
    assertThat(detail.path("currentStageRequiredCount").asInt()).isEqualTo(4);
    assertThat(detail.path("remainingCountForNextStage").asLong()).isEqualTo(4);
    assertThat(detail.path("totalStages").asInt()).isEqualTo(6);
    assertThat(detail.path("isMaxStage").asBoolean()).isFalse();
    assertThat(detail.path("currentActivityRecord").isNull()).isTrue();
    assertThat(detail.path("lastActivityAt").isNull()).isTrue();
    assertThat(detail.path("plantedAt")).isEqualTo(result(planted).path("plantedAt"));
    assertMatchesList(owner, treeId, detail);
  }

  @Test
  void countsUniqueGrowthAcrossStagesAndCurrentStageExactlyAsApi18() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), 4);
    long previous = stage(hobbyId, 0, "씨앗");
    long current = stage(hobbyId, 1, "새싹");
    long treeId = tree(owner.id(), hobbyId, 1, "ACTIVE");
    var repeated = record(treeId, previous, "COMPLETED", 0, 7, "{}", UPDATED);
    record(treeId, current, "COMPLETED", 1, 1, "{}", UPDATED);
    record(treeId, current, "COMPLETED", 1, 1, "{}", UPDATED);
    record(treeId, current, "COMPLETED", null, 1, "{}", UPDATED);
    record(treeId, current, "PARTIAL", null, 1, "{}", UPDATED);
    record(treeId, current, "IN_PROGRESS", null, 1, "{}", UPDATED);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO activity_records(user_hobby_id,activity_id) VALUES (?,?)",
                    treeId,
                    repeated.activityId()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    var detail = success(query(owner.token(), Long.toString(treeId)));

    assertThat(detail.path("distinctCompletedActivityCount").asLong()).isEqualTo(3);
    assertThat(detail.path("currentStageCompletedCount").asLong()).isEqualTo(2);
    assertThat(detail.path("requiredCountForNextStage").asInt()).isEqualTo(4);
    assertThat(detail.path("remainingCountForNextStage").asLong()).isEqualTo(2);
    assertMatchesList(owner, treeId, detail);
    assertThat(
            jdbc.queryForObject(
                "SELECT current_stage_number FROM user_hobbies WHERE user_hobby_id=?",
                Integer.class,
                treeId))
        .isEqualTo(1);
  }

  @Test
  void preservesTerminalStageEvenWhenOnlyPartOfTheCatalogIsRegistered() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), 3);
    long previous = stage(hobbyId, 4, "이전");
    stage(hobbyId, 5, "세계수");
    long treeId = tree(owner.id(), hobbyId, 5, "ENDED");
    record(treeId, previous, "COMPLETED", 4, 1, "{}", UPDATED);

    var detail = success(query(owner.token(), Long.toString(treeId)));

    assertThat(detail.path("currentStageNumber").asInt()).isEqualTo(5);
    assertThat(detail.path("currentStageName").asText()).isEqualTo("세계수");
    assertThat(detail.path("totalStages").asInt()).isEqualTo(6);
    assertThat(detail.path("isMaxStage").asBoolean()).isTrue();
    assertThat(detail.path("requiredCountForNextStage").isNull()).isTrue();
    assertThat(detail.path("currentStageRequiredCount").isNull()).isTrue();
    assertThat(detail.path("remainingCountForNextStage").asLong()).isZero();
    assertThat(detail.path("distinctCompletedActivityCount").asLong()).isEqualTo(1);
    assertThat(detail.path("currentStageCompletedCount").asLong()).isZero();
    assertMatchesList(owner, treeId, detail);
  }

  @Test
  void returnsNullStageNameButKeepsSixPolicyStagesWhenDefinitionsAreMissing() throws Exception {
    var owner = signIn();
    long treeId = tree(owner.id(), hobby(owner.id(), 3), 0, "ACTIVE");

    var detail = success(query(owner.token(), Long.toString(treeId)));

    assertThat(detail.path("currentStageName").isNull()).isTrue();
    assertThat(detail.path("totalStages").asInt()).isEqualTo(6);
    assertThat(detail.path("isMaxStage").asBoolean()).isFalse();
    assertMatchesList(owner, treeId, detail);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void keepsThePolicyStageCountAndTerminalFlagConsistentAcrossAllStoredStages(int stageNumber)
      throws Exception {
    var owner = signIn();
    long treeId = tree(owner.id(), hobby(owner.id(), 4), stageNumber, "ACTIVE");

    var detail = success(query(owner.token(), Long.toString(treeId)));

    assertThat(detail.path("currentStageNumber").asInt()).isEqualTo(stageNumber);
    assertThat(detail.path("totalStages").asInt()).isEqualTo(6);
    assertThat(detail.path("isMaxStage").asBoolean())
        .isEqualTo(stageNumber == detail.path("totalStages").asInt() - 1);
    assertThat(detail.path("currentStageName").isNull()).isTrue();
    if (stageNumber == 5) {
      assertThat(detail.path("requiredCountForNextStage").isNull()).isTrue();
      assertThat(detail.path("currentStageRequiredCount").isNull()).isTrue();
      assertThat(detail.path("remainingCountForNextStage").asLong()).isZero();
    } else {
      assertThat(detail.path("requiredCountForNextStage").asInt()).isEqualTo(4);
      assertThat(detail.path("currentStageRequiredCount").asInt()).isEqualTo(4);
      assertThat(detail.path("remainingCountForNextStage").asLong()).isEqualTo(4);
    }
    assertMatchesList(owner, treeId, detail);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "PAUSED", "ENDED"})
  void permitsEveryTreeStatus(String status) throws Exception {
    var owner = signIn();
    long treeId = tree(owner.id(), hobby(owner.id(), 3), 0, status);

    assertThat(success(query(owner.token(), Long.toString(treeId))).path("treeStatus").asText())
        .isEqualTo(status);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"{}", "{\"progressPercent\":50,\"completedStepIds\":[1]}", "{\"1\":true}"})
  void returnsTheRealCurrentActivityButKeepsUnverifiableProgressUnknown(String progress)
      throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), 3);
    long stageId = stage(hobbyId, 0, "씨앗");
    long treeId = tree(owner.id(), hobbyId, 0, "PAUSED");
    var current = record(treeId, stageId, "IN_PROGRESS", null, 1, progress, UPDATED);

    var detail = success(query(owner.token(), Long.toString(treeId)));
    var activity = detail.path("currentActivityRecord");

    assertThat(activity.propertyNames())
        .containsExactlyInAnyOrder("recordId", "activityId", "title", "progressPercent");
    assertThat(activity.path("recordId").asLong()).isEqualTo(current.id());
    assertThat(activity.path("activityId").asLong()).isEqualTo(current.activityId());
    assertThat(activity.path("title").asText()).isEqualTo("실제 활동 제목");
    assertThat(activity.path("progressPercent").isNull()).isTrue();
    assertThat(Instant.parse(detail.path("lastActivityAt").asText()))
        .isEqualTo(Instant.parse(UPDATED));
  }

  @Test
  void selectsTheMostRecentlyUpdatedInProgressRecordWithAnIdTieBreaker() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), 3);
    long stageId = stage(hobbyId, 0, "씨앗");
    long treeId = tree(owner.id(), hobbyId, 0, "ACTIVE");
    record(treeId, stageId, "IN_PROGRESS", null, 1, "{}", UPDATED);
    var selected = record(treeId, stageId, "IN_PROGRESS", null, 1, "{}", UPDATED);
    record(treeId, stageId, "COMPLETED", null, 1, "{}", "2026-11-02T02:00:00Z");
    long otherTree = tree(otherUser(), hobbyId, 0, "ACTIVE");
    record(otherTree, stageId, "IN_PROGRESS", null, 1, "{}", "2026-11-03T02:00:00Z");

    var detail = success(query(owner.token(), Long.toString(treeId)));

    assertThat(detail.path("currentActivityRecord").path("recordId").asLong())
        .isEqualTo(selected.id());
    assertThat(Instant.parse(detail.path("lastActivityAt").asText()))
        .isEqualTo(Instant.parse("2026-11-02T02:00:00Z"));
  }

  @Test
  void usesTheLatestRecordUpdateIncludingProgressChangesForLastActivityAt() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), 3);
    long stageId = stage(hobbyId, 0, "씨앗");
    long treeId = tree(owner.id(), hobbyId, 0, "ACTIVE");
    record(treeId, stageId, "COMPLETED", 0, 1, "{}", UPDATED);
    var recent = record(treeId, stageId, "IN_PROGRESS", null, 1, "{}", "2026-11-03T02:00:00Z");
    jdbc.update(
        "UPDATE activity_records SET updated_at=?::timestamptz WHERE activity_record_id=?",
        "2026-11-04T02:00:00Z",
        recent.id());

    var detail = success(query(owner.token(), Long.toString(treeId)));

    assertThat(Instant.parse(detail.path("lastActivityAt").asText()))
        .isEqualTo(Instant.parse("2026-11-04T02:00:00Z"));
  }

  @Test
  void returnsTheSameNotFoundErrorForAnotherOwnersTreeAndAMissingTree() throws Exception {
    var owner = signIn();
    long foreign = tree(otherUser(), hobby(owner.id(), 3), 0, "ACTIVE");

    assertError(query(owner.token(), Long.toString(foreign)), 404, "TREE404_1");
    assertError(query(owner.token(), Long.toString(Long.MAX_VALUE)), 404, "TREE404_1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
  void rejectsInvalidIds(String id) throws Exception {
    assertError(query(signIn().token(), id), 400, "COMMON400_1");
  }

  @Test
  void rejectsMissingExpiredAndRevokedJwt() throws Exception {
    var owner = signIn();
    long treeId = tree(owner.id(), hobby(owner.id(), 3), 0, "ACTIVE");
    String id = Long.toString(treeId);
    assertError(query(null, id), 401, "AUTH401_2");
    assertError(query("invalid", id), 401, "AUTH401_2");
    var issuer =
        new JwtTokenService(
            encoder, authProperties, Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));
    String sid = decoder.decode(owner.token()).getClaimAsString("sid");
    assertError(
        query(issuer.issue(users.findById(owner.id()).orElseThrow(), sid, "access"), id),
        401,
        "AUTH401_2");
    redis.delete("auth:session:" + owner.id());
    assertError(query(owner.token(), id), 401, "AUTH401_2");
  }

  @Test
  void rejectsPendingOnboardingWithAValidToken() throws Exception {
    var signedIn = result(login());
    assertError(query(signedIn.path("accessToken").asText(), "1"), 403, "AUTH403_2");
  }

  @ParameterizedTest
  @CsvSource({"SUSPENDED, AUTH403_1", "WITHDRAWN, AUTH403_3"})
  void rejectsInactiveMembers(String status, String expected) throws Exception {
    var owner = signIn();
    long treeId = tree(owner.id(), hobby(owner.id(), 3), 0, "ACTIVE");
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
        owner.id());
    assertError(query(owner.token(), Long.toString(treeId)), 403, expected);
  }

  private SignedInUser signIn() throws Exception {
    var response = login();
    assertThat(response.statusCode()).isEqualTo(200);
    var loggedIn = result(response);
    long id = loggedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return new SignedInUser(id, loggedIn.path("accessToken").asText());
  }

  private long otherUser() {
    long id =
        jdbc.queryForObject(
            "INSERT INTO users(provider,provider_user_id,account_status) VALUES ('KAKAO',?,'ACTIVE') RETURNING user_id",
            Long.class,
            UUID.randomUUID().toString());
    createdUsers.add(id);
    return id;
  }

  private long hobby(long ownerId, int required) {
    return jdbc.queryForObject(
        "INSERT INTO hobbies(hobby_code,hobby_name,activities_per_stage,thumbnail_url) VALUES (?,'상세 테스트',?,'https://example.com/tree.png') RETURNING hobby_id",
        Long.class,
        "profile-test-" + ownerId + "-" + UUID.randomUUID().toString().substring(0, 12),
        required);
  }

  private long stage(long hobbyId, int number, String name) {
    return jdbc.queryForObject(
        "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,?,?) RETURNING hobby_stage_id",
        Long.class,
        hobbyId,
        number,
        name);
  }

  private long tree(long ownerId, long hobbyId, int number, String status) {
    return jdbc.queryForObject(
        "INSERT INTO user_hobbies(user_id,hobby_id,current_stage_number,tree_status) VALUES (?,?,?,?) RETURNING user_hobby_id",
        Long.class,
        ownerId,
        hobbyId,
        number,
        status);
  }

  private SeedRecord record(
      long treeId,
      long stageId,
      String status,
      Integer creditStage,
      int attempts,
      String progress,
      String updatedAt) {
    long activityId =
        jdbc.queryForObject(
            "INSERT INTO activities(hobby_stage_id,activity_code,activity_name) VALUES (?,?,'실제 활동 제목') RETURNING activity_id",
            Long.class,
            stageId,
            UUID.randomUUID().toString());
    long id =
        jdbc.queryForObject(
            """
        INSERT INTO activity_records
          (user_hobby_id,activity_id,activity_status,growth_counted,growth_stage_number,
           attempt_count,step_progress,started_at,finished_at,updated_at)
        VALUES (?,?,?,?,?,?,?::jsonb,'2026-11-01T00:00:00Z',
          CASE WHEN ?='COMPLETED' THEN '2026-11-01T01:00:00Z'::timestamptz END,
          ?::timestamptz) RETURNING activity_record_id
        """,
            Long.class,
            treeId,
            activityId,
            status,
            creditStage != null,
            creditStage,
            attempts,
            progress,
            status,
            updatedAt);
    return new SeedRecord(id, activityId);
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
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + "/" + id))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode success(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var root = mapper.readTree(response.body());
    assertThat(root.propertyNames())
        .containsExactlyInAnyOrder("isSuccess", "code", "message", "result");
    assertThat(root.path("isSuccess").asBoolean()).isTrue();
    assertThat(root.path("code").asText()).isEqualTo("TREE200_2");
    assertThat(root.path("message").asText()).isEqualTo("취미 나무 상세와 성장 현황을 조회했습니다.");
    return root.path("result");
  }

  private void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    var root = mapper.readTree(response.body());
    assertThat(root.path("isSuccess").asBoolean()).isFalse();
    assertThat(root.path("code").asText()).isEqualTo(code);
    assertThat(root.path("result").isNull()).isTrue();
  }

  private void assertMatchesList(SignedInUser owner, long treeId, JsonNode detail)
      throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .header("Authorization", "Bearer " + owner.token())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    var row = result(response).path("items").get(0);
    assertThat(row.path("userHobbyId").asLong()).isEqualTo(treeId);
    for (String field : COMMON_FIELDS)
      assertThat(detail.path(field)).as(field).isEqualTo(row.path(field));
  }

  private record SignedInUser(long id, String token) {}

  private record SeedRecord(long id, long activityId) {}
}
