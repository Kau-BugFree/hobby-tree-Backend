package com.hobbytree.hobbytree.domain.tree;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.auth.service.JwtTokenService;
import com.hobbytree.hobbytree.support.GrowthLedgerFixtures;
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
class UserHobbyRoadmapIntegrationTests extends IntegrationTestSupport {
  private static final String PATH = "/api/v1/user-hobbies";

  @Test
  void returnsTheExactContractForANewTreePlantedThroughApi19() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), 4);
    long seed = stage(hobbyId, 0, "실제 씨앗 이름");
    long next = stage(hobbyId, 1, "실제 새싹 이름");
    long activityId = activity(seed, "PUBLISHED");
    var planted =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .header("Authorization", "Bearer " + owner.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"hobbyId\":" + hobbyId + "}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(planted.statusCode()).as(planted.body()).isEqualTo(201);
    long treeId = result(planted).path("userHobbyId").asLong();

    var roadmap = success(query(owner.token(), Long.toString(treeId)));

    assertThat(roadmap.propertyNames())
        .containsExactlyInAnyOrder(
            "userHobbyId",
            "currentStageNumber",
            "distinctCompletedActivityCount",
            "growthRule",
            "stages");
    assertThat(roadmap.path("userHobbyId").asLong()).isEqualTo(treeId);
    assertThat(roadmap.path("currentStageNumber").asInt()).isEqualTo(1);
    assertThat(roadmap.path("distinctCompletedActivityCount").asLong()).isZero();
    assertThat(roadmap.path("growthRule").propertyNames())
        .containsExactly("distinctActivitiesPerStage");
    assertThat(roadmap.path("growthRule").path("distinctActivitiesPerStage").asInt()).isEqualTo(4);
    var rows = roadmap.path("stages");
    assertThat(rows.size()).isEqualTo(2);
    assertStage(rows.get(0), seed, 1, "실제 씨앗 이름", "CURRENT", 0, List.of(activityId));
    assertStage(rows.get(1), next, 2, "실제 새싹 이름", "LOCKED", 4, List.of());
    assertMatchesListAndDetail(owner, treeId, roadmap);
  }

  @ParameterizedTest
  @CsvSource({"0, 1", "1, 3", "2, 4", "3, 2", "4, 7", "5, 4"})
  void mapsEveryStoredStageAndUsesTheActualGrowthRule(int current, int required) throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), required);
    long[] stageIds = new long[6];
    // Insert in reverse order: the database must order by stored stage, not insertion ID.
    for (int number = 5; number >= 0; number--)
      stageIds[number] = stage(hobbyId, number, "단계 " + number);
    long treeId = tree(owner.id(), hobbyId, current, "ACTIVE");

    var roadmap = success(query(owner.token(), Long.toString(treeId)));

    assertThat(roadmap.path("currentStageNumber").asInt()).isEqualTo(current + 1);
    assertThat(roadmap.path("stages").size()).isEqualTo(6);
    for (int number = 0; number <= 5; number++) {
      String state = number < current ? "COMPLETED" : number == current ? "CURRENT" : "LOCKED";
      assertStage(
          roadmap.path("stages").get(number),
          stageIds[number],
          number + 1,
          "단계 " + number,
          state,
          (long) number * required,
          List.of());
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT current_stage_number FROM user_hobbies WHERE user_hobby_id=?",
                Integer.class,
                treeId))
        .isEqualTo(current);
    assertMatchesListAndDetail(owner, treeId, roadmap);
  }

  @Test
  void countsOnlyCreditedDistinctActivitiesAcrossStagesWithoutCountingAttemptsOrOtherTrees()
      throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), 4);
    long previous = stage(hobbyId, 0, "이전");
    long current = stage(hobbyId, 1, "현재");
    long treeId = tree(owner.id(), hobbyId, 1, "ACTIVE");
    long repeated = activity(previous, "PUBLISHED");
    record(treeId, repeated, "COMPLETED", 0, 8);
    // Publication changes affect representative IDs, not historical growth already earned.
    jdbc.update("UPDATE activities SET review_status='DRAFT' WHERE activity_id=?", repeated);
    record(treeId, activity(current, "PUBLISHED"), "COMPLETED", 1, 1);
    record(treeId, activity(current, "PUBLISHED"), "COMPLETED", null, 1);
    record(treeId, activity(current, "PUBLISHED"), "PARTIAL", null, 1);
    record(treeId, activity(current, "PUBLISHED"), "IN_PROGRESS", null, 1);
    long foreignTree = tree(otherUser(), hobbyId, 1, "ACTIVE");
    record(foreignTree, repeated, "COMPLETED", 0, 1);
    assertThatThrownBy(() -> record(treeId, repeated, "COMPLETED", 0, 1))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    var roadmap = success(query(owner.token(), Long.toString(treeId)));

    assertThat(roadmap.path("distinctCompletedActivityCount").asLong()).isEqualTo(2);
    assertThat(roadmap.path("stages").get(0).path("representativeActivityIds").size()).isZero();
    // Historical data may not match a threshold; reads never rewrite the authoritative stage.
    assertThat(roadmap.path("stages").get(1).path("state").asText()).isEqualTo("CURRENT");
    assertMatchesListAndDetail(owner, treeId, roadmap);
  }

  @Test
  void doesNotAdvanceAStoredStageWhenCreditsReachAnUnlockThreshold() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), 1);
    long seed = stage(hobbyId, 0, "씨앗");
    stage(hobbyId, 1, "새싹");
    long treeId = tree(owner.id(), hobbyId, 0, "ACTIVE");
    record(treeId, activity(seed, "PUBLISHED"), "COMPLETED", 0, 1);

    var roadmap = success(query(owner.token(), Long.toString(treeId)));

    assertThat(roadmap.path("distinctCompletedActivityCount").asLong()).isEqualTo(1);
    assertThat(roadmap.path("currentStageNumber").asInt()).isEqualTo(1);
    assertThat(roadmap.path("stages").get(0).path("state").asText()).isEqualTo("CURRENT");
    assertThat(roadmap.path("stages").get(1).path("state").asText()).isEqualTo("LOCKED");
    assertMatchesListAndDetail(owner, treeId, roadmap);
  }

  @Test
  void returnsOnlyPublishedActivitiesInIdOrderWithoutDuplicatingStagesOrInventingALimit()
      throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), 3);
    long first = stage(hobbyId, 0, "씨앗");
    long second = stage(hobbyId, 1, "새싹");
    long last = stage(hobbyId, 5, "세계수");
    long treeId = tree(owner.id(), hobbyId, 0, "ACTIVE");
    activity(first, "DRAFT");
    long a = activity(first, "PUBLISHED");
    activity(first, "REVIEW");
    long b = activity(first, "PUBLISHED");
    activity(first, "REJECTED");
    long c = activity(first, "PUBLISHED");
    long d = activity(first, "PUBLISHED");
    activity(second, "DRAFT");
    long lockedActivity = activity(last, "PUBLISHED");
    activity(stage(hobby(owner.id(), 3), 0, "다른 취미"), "PUBLISHED");

    var rows = success(query(owner.token(), Long.toString(treeId))).path("stages");

    assertThat(rows.size()).isEqualTo(3);
    assertStage(rows.get(0), first, 1, "씨앗", "CURRENT", 0, List.of(a, b, c, d));
    assertStage(rows.get(1), second, 2, "새싹", "LOCKED", 3, List.of());
    assertStage(rows.get(2), last, 6, "세계수", "LOCKED", 15, List.of(lockedActivity));
  }

  @Test
  void keepsMissingStageDefinitionsMissingAndUsesStoredNumbersForSparseThresholds()
      throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), 4);
    long first = stage(hobbyId, 0, "씨앗");
    long later = stage(hobbyId, 3, "나무");
    long treeId = tree(owner.id(), hobbyId, 1, "ACTIVE");

    var roadmap = success(query(owner.token(), Long.toString(treeId)));

    assertThat(roadmap.path("currentStageNumber").asInt()).isEqualTo(2);
    assertThat(roadmap.path("stages").size()).isEqualTo(2);
    assertStage(roadmap.path("stages").get(0), first, 1, "씨앗", "COMPLETED", 0, List.of());
    assertStage(roadmap.path("stages").get(1), later, 4, "나무", "LOCKED", 12, List.of());
  }

  @Test
  void returnsAnEmptyArrayWhenNoStageDefinitionsExist() throws Exception {
    var owner = signIn();
    long treeId = tree(owner.id(), hobby(owner.id(), 3), 0, "ACTIVE");
    var roadmap = success(query(owner.token(), Long.toString(treeId)));
    assertThat(roadmap.path("stages").isArray()).isTrue();
    assertThat(roadmap.path("stages").size()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "PAUSED", "ENDED"})
  void permitsEveryTreeStatusAndPreservesTreeGrowthAndActivityRecords(String status)
      throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id(), 4);
    long stageId = stage(hobbyId, 0, "씨앗");
    long treeId = tree(owner.id(), hobbyId, 0, status);
    record(treeId, activity(stageId, "PUBLISHED"), "COMPLETED", 0, 3);
    record(treeId, activity(stageId, "DRAFT"), "IN_PROGRESS", null, 2);
    var treeBefore = jdbc.queryForMap("SELECT * FROM user_hobbies WHERE user_hobby_id=?", treeId);
    var recordsBefore =
        jdbc.queryForList(
            "SELECT * FROM activity_records WHERE user_hobby_id=? ORDER BY activity_record_id",
            treeId);

    success(query(owner.token(), Long.toString(treeId)));

    assertThat(jdbc.queryForMap("SELECT * FROM user_hobbies WHERE user_hobby_id=?", treeId))
        .isEqualTo(treeBefore);
    assertThat(
            jdbc.queryForList(
                "SELECT * FROM activity_records WHERE user_hobby_id=? ORDER BY activity_record_id",
                treeId))
        .isEqualTo(recordsBefore);
  }

  @Test
  void returnsTheSameNotFoundForAnotherOwnersTreeAndAMissingTree() throws Exception {
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
  void rejectsMissingForgedExpiredAndRevokedTokens() throws Exception {
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
  void rejectsPendingOnboarding() throws Exception {
    assertError(query(result(login()).path("accessToken").asText(), "1"), 403, "AUTH403_2");
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
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
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
        "INSERT INTO hobbies(hobby_code,hobby_name,activities_per_stage) VALUES (?,'로드맵 테스트',?) RETURNING hobby_id",
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

  private long activity(long stageId, String reviewStatus) {
    return jdbc.queryForObject(
        "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status) VALUES (?,?,'실제 대표 활동',?) RETURNING activity_id",
        Long.class,
        stageId,
        UUID.randomUUID().toString(),
        reviewStatus);
  }

  private void record(
      long treeId, long activityId, String status, Integer creditStage, int attempts) {
    jdbc.update(
        """
        INSERT INTO activity_records(user_hobby_id,activity_id,activity_status,
          growth_counted,growth_stage_number,attempt_count)
        VALUES (?,?,?,?,?,?)
        """,
        treeId,
        activityId,
        status,
        creditStage != null,
        creditStage,
        attempts);
    GrowthLedgerFixtures.copyCreditIfCounted(jdbc, treeId, activityId);
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
    return get(token, PATH + "/" + id + "/roadmap");
  }

  private HttpResponse<String> get(String token, String path) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode success(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    var root = mapper.readTree(response.body());
    assertThat(root.propertyNames())
        .containsExactlyInAnyOrder("isSuccess", "code", "message", "result");
    assertThat(root.path("isSuccess").asBoolean()).isTrue();
    assertThat(root.path("code").asText()).isEqualTo("TREE200_4");
    assertThat(root.path("message").asText()).isEqualTo("나무 성장 로드맵을 조회했습니다.");
    return root.path("result");
  }

  private void assertStage(
      JsonNode row,
      long id,
      int number,
      String name,
      String state,
      long unlock,
      List<Long> activities) {
    assertThat(row.propertyNames())
        .containsExactlyInAnyOrder(
            "stageId",
            "stageNumber",
            "name",
            "state",
            "unlockAtDistinctCompletedCount",
            "representativeActivityIds");
    assertThat(row.path("stageId").asLong()).isEqualTo(id);
    assertThat(row.path("stageNumber").asInt()).isEqualTo(number);
    assertThat(row.path("name").asText()).isEqualTo(name);
    assertThat(row.path("state").asText()).isEqualTo(state);
    assertThat(row.path("unlockAtDistinctCompletedCount").asLong()).isEqualTo(unlock);
    assertThat(row.path("representativeActivityIds").isArray()).isTrue();
    assertThat(row.path("representativeActivityIds").size()).isEqualTo(activities.size());
    for (int i = 0; i < activities.size(); i++)
      assertThat(row.path("representativeActivityIds").get(i).asLong())
          .isEqualTo(activities.get(i));
  }

  private void assertMatchesListAndDetail(SignedInUser owner, long treeId, JsonNode roadmap)
      throws Exception {
    var detailResponse = get(owner.token(), PATH + "/" + treeId);
    var listResponse = get(owner.token(), PATH);
    assertThat(detailResponse.statusCode()).as(detailResponse.body()).isEqualTo(200);
    assertThat(listResponse.statusCode()).as(listResponse.body()).isEqualTo(200);
    for (var node : List.of(result(detailResponse), result(listResponse).path("items").get(0))) {
      assertThat(node.path("userHobbyId").asLong()).isEqualTo(treeId);
      assertThat(node.path("currentStageNumber")).isEqualTo(roadmap.path("currentStageNumber"));
      assertThat(node.path("distinctCompletedActivityCount"))
          .isEqualTo(roadmap.path("distinctCompletedActivityCount"));
    }
  }

  private void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    var root = mapper.readTree(response.body());
    assertThat(root.path("isSuccess").asBoolean()).isFalse();
    assertThat(root.path("code").asText()).isEqualTo(code);
    assertThat(root.path("result").isNull()).isTrue();
  }

  private record SignedInUser(long id, String token) {}
}
