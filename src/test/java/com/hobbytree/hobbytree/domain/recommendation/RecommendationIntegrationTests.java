package com.hobbytree.hobbytree.domain.recommendation;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class RecommendationIntegrationTests extends IntegrationTestSupport {
  private static final String PATH = "/api/v1/recommendations";
  private final List<Long> createdHobbyIds = new ArrayList<>();

  @Test
  void createsRecommendationAndReturnsTheExactContract() throws Exception {
    long userId = activeUser();
    seedPreferences(userId, "LOW", 30, "INDOOR", "BEGINNER", "[1]", 1);
    long hobbyId = hobby(userId, "COOKING", "INDOOR");
    introActivity(stage(hobbyId), 30, 30000, "BEGINNER");

    var response =
        create(token(userId), UUID.randomUUID().toString(), body(1, null, excludeAllBut(hobbyId)));
    var result = assertCreatedResult(response);

    assertThat(fieldNames(result))
        .containsExactlyInAnyOrder(
            "recommendationId",
            "preferencesVersion",
            "createdAt",
            "items",
            "requestedCount",
            "returnedCount");
    assertThat(result.path("preferencesVersion").asInt()).isEqualTo(1);
    assertThat(result.path("createdAt").asText()).isNotBlank();
    assertThat(result.path("requestedCount").asInt()).isEqualTo(3);
    assertThat(result.path("returnedCount").asInt()).isEqualTo(1);
    assertThat(result.path("items")).hasSize(1);

    var item = result.path("items").get(0);
    assertThat(fieldNames(item))
        .containsExactlyInAnyOrder("hobbyId", "name", "thumbnailUrl", "reason", "matchedCriteria");
    assertThat(item.path("hobbyId").asLong()).isEqualTo(hobbyId);
    List<String> criteria = new ArrayList<>();
    item.path("matchedCriteria").forEach(n -> criteria.add(n.asText()));
    assertThat(criteria)
        .containsExactly(
            "WITHIN_BUDGET", "WITHIN_TIME", "INDOOR", "PREFERRED_CATEGORY", "BEGINNER_FRIENDLY");
    assertThat(item.path("reason").asText())
        .isEqualTo(
            "초기 준비 비용이 예산 안에 들어고 30분 내로 시작할 수 있어고 실내에서 즐길 수 있어고 선호하는 분야와 맞아고 입문자도"
                + " 쉽게 시작할 수 있어 추천합니다.");

    long recommendationId = result.path("recommendationId").asLong();
    assertThat(
            jdbc.queryForObject(
                "SELECT user_id FROM recommendations WHERE recommendation_id=?",
                Long.class,
                recommendationId))
        .isEqualTo(userId);
    assertThat(
            jdbc.queryForObject(
                "SELECT recommendation_version FROM recommendations WHERE recommendation_id=?",
                String.class,
                recommendationId))
        .isEqualTo("RULE_BASED_V1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM recommendation_items WHERE recommendation_id=? AND hobby_id=?",
                Integer.class,
                recommendationId,
                hobbyId))
        .isEqualTo(1);
  }

  @Test
  void excludesAlreadyOwnedAndExplicitlyExcludedHobbies() throws Exception {
    long userId = activeUser();
    seedPreferences(userId, "HIGH", 120, "ANY", "ANY", "[]", 1);
    long owned = hobby(userId, "COOKING", "INDOOR");
    introActivity(stage(owned), 30, 1000, "BEGINNER");
    seedOwnership(userId, owned);
    long excluded = hobby(userId, "READING", "INDOOR");
    introActivity(stage(excluded), 30, 1000, "BEGINNER");
    long eligible = hobby(userId, "INSTRUMENT", "INDOOR");
    introActivity(stage(eligible), 30, 1000, "BEGINNER");

    List<Long> exclude = new ArrayList<>(excludeAllBut(owned, excluded, eligible));
    exclude.add(excluded);

    var response = create(token(userId), UUID.randomUUID().toString(), body(1, null, exclude));
    var result = assertCreatedResult(response);
    assertThat(result.path("items")).hasSize(1);
    assertThat(result.path("items").get(0).path("hobbyId").asLong()).isEqualTo(eligible);
  }

  @Test
  void returnsNoCandidatesWhenEverythingIsFilteredOut() throws Exception {
    long userId = activeUser();
    seedPreferences(userId, "LOW", 15, "INDOOR", "BEGINNER", "[1]", 1);
    long tooHard = hobby(userId, "COOKING", "INDOOR");
    introActivity(stage(tooHard), 30, 1000, "INTERMEDIATE");

    var response =
        create(token(userId), UUID.randomUUID().toString(), body(1, null, excludeAllBut(tooHard)));
    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(code(response)).isEqualTo("RECOMMENDATION422_1");
  }

  @Test
  void rejectsWhenPreferencesVersionDoesNotMatch() throws Exception {
    long userId = activeUser();
    seedPreferences(userId, "LOW", 30, "INDOOR", "BEGINNER", "[1]", 1);

    var response = create(token(userId), UUID.randomUUID().toString(), body(2, null, List.of()));
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(code(response)).isEqualTo("RECOMMENDATION409_1");
  }

  @Test
  void rejectsWhenNoPreferencesHaveBeenSavedYet() throws Exception {
    long userId = activeUser();

    var response = create(token(userId), UUID.randomUUID().toString(), body(1, null, List.of()));
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(code(response)).isEqualTo("RECOMMENDATION409_1");
  }

  @Test
  void aPreferencesRowTouchedOnlyByNotificationSettingsStillCountsAsUnset() throws Exception {
    long userId = activeUser();
    jdbc.update(
        "INSERT INTO user_preferences (user_id, notification_frequency) VALUES (?, 'WEEKLY')",
        userId);

    var response = create(token(userId), UUID.randomUUID().toString(), body(0, null, List.of()));
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(code(response)).isEqualTo("RECOMMENDATION409_1");
  }

  @Test
  void replaysTheSameResultForARetryWithTheSameIdempotencyKeyAndBody() throws Exception {
    long userId = activeUser();
    seedPreferences(userId, "LOW", 30, "INDOOR", "BEGINNER", "[1]", 1);
    long hobbyId = hobby(userId, "COOKING", "INDOOR");
    introActivity(stage(hobbyId), 30, 30000, "BEGINNER");
    String key = UUID.randomUUID().toString();
    String requestBody = body(1, null, excludeAllBut(hobbyId));

    var first = assertCreatedResult(create(token(userId), key, requestBody));
    var second = assertCreatedResult(create(token(userId), key, requestBody));

    assertThat(second.path("recommendationId").asLong())
        .isEqualTo(first.path("recommendationId").asLong());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM recommendations WHERE user_id=?", Integer.class, userId))
        .isEqualTo(1);
  }

  @Test
  void rejectsReusingTheSameIdempotencyKeyWithADifferentBody() throws Exception {
    long userId = activeUser();
    seedPreferences(userId, "LOW", 30, "INDOOR", "BEGINNER", "[1]", 1);
    long hobbyId = hobby(userId, "COOKING", "INDOOR");
    introActivity(stage(hobbyId), 30, 30000, "BEGINNER");
    String key = UUID.randomUUID().toString();

    assertCreatedResult(create(token(userId), key, body(1, null, excludeAllBut(hobbyId))));
    var conflict = create(token(userId), key, body(1, 2, excludeAllBut(hobbyId)));
    assertThat(conflict.statusCode()).isEqualTo(409);
    assertThat(code(conflict)).isEqualTo("COMMON409_1");
  }

  @Test
  void rejectsAKeyWhoseFirstClaimIsStillInProgress() throws Exception {
    long userId = activeUser();
    seedPreferences(userId, "LOW", 30, "INDOOR", "BEGINNER", "[1]", 1);
    String key = UUID.randomUUID().toString();
    String requestBody = body(1, null, List.of());
    String hash = RedisAuthStore.hash("v=1;count=3;exclude=");
    jdbc.update(
        "INSERT INTO idempotency_keys(user_id,endpoint,idempotency_key,request_hash,status)"
            + " VALUES (?,?,?,?,'PENDING')",
        userId,
        "POST /api/v1/recommendations",
        key,
        hash);

    var response = create(token(userId), key, requestBody);
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(code(response)).isEqualTo("COMMON409_2");
  }

  @Test
  void releasesTheClaimWhenTheBusinessValidationFailsSoARetryCanSucceed() throws Exception {
    long userId = activeUser();
    String key = UUID.randomUUID().toString();
    String requestBody = body(1, null, List.of());

    var failed = create(token(userId), key, requestBody);
    assertThat(failed.statusCode()).isEqualTo(409);
    assertThat(code(failed)).isEqualTo("RECOMMENDATION409_1");

    seedPreferences(userId, "LOW", 30, "INDOOR", "BEGINNER", "[1]", 1);
    long hobbyId = hobby(userId, "COOKING", "INDOOR");
    introActivity(stage(hobbyId), 30, 30000, "BEGINNER");
    var retried = create(token(userId), key, body(1, null, excludeAllBut(hobbyId)));
    assertThat(retried.statusCode()).isEqualTo(201);
  }

  @Test
  void requiresTheIdempotencyKeyHeader() throws Exception {
    long userId = activeUser();
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token(userId))
            .POST(HttpRequest.BodyPublishers.ofString(body(1, null, List.of())));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(code(response)).isEqualTo("COMMON400_1");
  }

  @Test
  void rejectsOutOfRangeCountAndMalformedExcludeHobbyIds() throws Exception {
    long userId = activeUser();
    seedPreferences(userId, "LOW", 30, "INDOOR", "BEGINNER", "[1]", 1);
    for (String invalidBody :
        List.of(
            body(1, 0, List.of()),
            body(1, 6, List.of()),
            body(null, null, List.of()),
            "{\"preferencesVersion\":1,\"excludeHobbyIds\":[1,1]}",
            "{\"preferencesVersion\":1,\"excludeHobbyIds\":[1,null]}")) {
      var response = create(token(userId), UUID.randomUUID().toString(), invalidBody);
      assertThat(response.statusCode()).as(invalidBody).isEqualTo(400);
      assertThat(code(response)).as(invalidBody).isEqualTo("COMMON400_1");
    }
  }

  @Test
  void rejectsPendingOnboarding() throws Exception {
    var signedIn = result(login());
    long userId = signedIn.path("userId").asLong();
    createdUsers.add(userId);
    var response =
        create(
            signedIn.path("accessToken").asText(),
            UUID.randomUUID().toString(),
            body(1, null, List.of()));
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(code(response)).isEqualTo("AUTH403_2");
  }

  @Test
  void requiresAuthentication() throws Exception {
    assertThat(create(null, UUID.randomUUID().toString(), body(1, null, List.of())).statusCode())
        .isEqualTo(401);
  }

  @Test
  void returnsTheLatestRecommendationWithTheExactContract() throws Exception {
    long userId = activeUser();
    seedPreferences(userId, "LOW", 30, "INDOOR", "BEGINNER", "[1]", 1);
    long hobbyId = hobby(userId, "COOKING", "INDOOR");
    introActivity(stage(hobbyId), 30, 30000, "BEGINNER");
    var created =
        assertCreatedResult(
            create(
                token(userId),
                UUID.randomUUID().toString(),
                body(1, null, excludeAllBut(hobbyId))));

    var response = latest(token(userId));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(code(response)).isEqualTo("RECOMMENDATION200_1");
    var result = result(response);

    assertThat(fieldNames(result))
        .containsExactlyInAnyOrder(
            "recommendationId",
            "preferencesVersion",
            "createdAt",
            "items",
            "requestedCount",
            "returnedCount",
            "isStale");
    assertThat(result.path("recommendationId").asLong())
        .isEqualTo(created.path("recommendationId").asLong());
    assertThat(result.path("preferencesVersion").asInt()).isEqualTo(1);
    assertThat(result.path("createdAt").asText()).isEqualTo(created.path("createdAt").asText());
    assertThat(result.path("requestedCount").asInt()).isEqualTo(1);
    assertThat(result.path("returnedCount").asInt()).isEqualTo(1);
    assertThat(result.path("isStale").asBoolean()).isFalse();

    var item = result.path("items").get(0);
    assertThat(fieldNames(item))
        .containsExactlyInAnyOrder("hobbyId", "name", "thumbnailUrl", "reason", "matchedCriteria");
    assertThat(item.path("hobbyId").asLong()).isEqualTo(hobbyId);
    assertThat(item).isEqualTo(created.path("items").get(0));
  }

  @Test
  void marksAsStaleWhenPreferencesVersionChangedSinceCreation() throws Exception {
    long userId = activeUser();
    seedPreferences(userId, "LOW", 30, "INDOOR", "BEGINNER", "[1]", 1);
    long hobbyId = hobby(userId, "COOKING", "INDOOR");
    introActivity(stage(hobbyId), 30, 30000, "BEGINNER");
    create(token(userId), UUID.randomUUID().toString(), body(1, null, excludeAllBut(hobbyId)));

    jdbc.update(
        "UPDATE user_preferences SET preferences_version = preferences_version + 1 WHERE user_id=?",
        userId);

    var result = result(latest(token(userId)));
    assertThat(result.path("preferencesVersion").asInt()).isEqualTo(1);
    assertThat(result.path("isStale").asBoolean()).isTrue();
  }

  @Test
  void excludesHiddenHobbiesAndAdjustsReturnedCount() throws Exception {
    long userId = activeUser();
    seedPreferences(userId, "HIGH", 120, "ANY", "ANY", "[]", 1);
    long visible = hobby(userId, "COOKING", "INDOOR");
    introActivity(stage(visible), 30, 1000, "BEGINNER");
    long hidden = hobby(userId, "READING", "INDOOR");
    introActivity(stage(hidden), 30, 1000, "BEGINNER");

    var created =
        assertCreatedResult(
            create(
                token(userId),
                UUID.randomUUID().toString(),
                body(1, 2, excludeAllBut(visible, hidden))));
    assertThat(created.path("returnedCount").asInt()).isEqualTo(2);

    jdbc.update("UPDATE hobbies SET is_supported=false WHERE hobby_id=?", hidden);

    var result = result(latest(token(userId)));
    assertThat(result.path("requestedCount").asInt()).isEqualTo(2);
    assertThat(result.path("returnedCount").asInt()).isEqualTo(1);
    assertThat(result.path("items")).hasSize(1);
    assertThat(result.path("items").get(0).path("hobbyId").asLong()).isEqualTo(visible);
  }

  @Test
  void returnsNotFoundWhenEveryItemIsHidden() throws Exception {
    long userId = activeUser();
    seedPreferences(userId, "LOW", 30, "INDOOR", "BEGINNER", "[1]", 1);
    long hobbyId = hobby(userId, "COOKING", "INDOOR");
    introActivity(stage(hobbyId), 30, 30000, "BEGINNER");
    create(token(userId), UUID.randomUUID().toString(), body(1, null, excludeAllBut(hobbyId)));
    jdbc.update("UPDATE hobbies SET is_supported=false WHERE hobby_id=?", hobbyId);

    var response = latest(token(userId));
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(code(response)).isEqualTo("RECOMMENDATION404_1");
  }

  @Test
  void returnsNotFoundWhenNoRecommendationExistsYet() throws Exception {
    long userId = activeUser();
    var response = latest(token(userId));
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(code(response)).isEqualTo("RECOMMENDATION404_1");
  }

  @Test
  void latestRejectsPendingOnboarding() throws Exception {
    var signedIn = result(login());
    long userId = signedIn.path("userId").asLong();
    createdUsers.add(userId);
    var response = latest(signedIn.path("accessToken").asText());
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(code(response)).isEqualTo("AUTH403_2");
  }

  @Test
  void latestRequiresAuthentication() throws Exception {
    assertThat(latest(null).statusCode()).isEqualTo(401);
  }

  // --- helpers -----------------------------------------------------------

  // Tokens are minted once at sign-in; cache the one activeUser() produces per test.
  private final java.util.Map<Long, String> tokensByUserId = new java.util.HashMap<>();

  private long activeUser() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    tokensByUserId.put(id, signedIn.path("accessToken").asText());
    return id;
  }

  private String token(long userId) {
    return tokensByUserId.get(userId);
  }

  private void seedPreferences(
      long userId,
      String budgetLevel,
      int minutes,
      String indoorOutdoor,
      String difficulty,
      String categoryIdsJson,
      int version) {
    jdbc.update(
        "INSERT INTO user_preferences(user_id,options_version,budget_level,"
            + "available_time_minutes,indoor_outdoor,difficulty_preference,preferred_category_ids,"
            + "preferences_version) VALUES (?,?,?,?,?,?,?::jsonb,?)",
        userId,
        "2026-11-07",
        budgetLevel,
        minutes,
        indoorOutdoor,
        difficulty,
        categoryIdsJson,
        version);
  }

  private long hobby(long userId, String category, String indoorOutdoor) {
    long id =
        jdbc.queryForObject(
            "INSERT INTO hobbies(hobby_code,hobby_name,category,indoor_outdoor) VALUES"
                + " (?,?,?,?) RETURNING hobby_id",
            Long.class,
            "profile-test-" + userId + "-" + UUID.randomUUID().toString().substring(0, 12),
            "추천 테스트 " + UUID.randomUUID(),
            category,
            indoorOutdoor);
    createdHobbyIds.add(id);
    return id;
  }

  private long stage(long hobbyId) {
    return jdbc.queryForObject(
        "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,0,'씨앗')"
            + " RETURNING hobby_stage_id",
        Long.class,
        hobbyId);
  }

  private void introActivity(long stageId, int durationMinutes, int costMin, String difficulty) {
    jdbc.update(
        "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,"
            + "expected_total_minutes,estimated_cost_min,difficulty,review_status) VALUES"
            + " (?,?,'Intro',?,?,?,'PUBLISHED')",
        stageId,
        "recommendation-test-" + UUID.randomUUID(),
        durationMinutes,
        costMin,
        difficulty);
  }

  private void seedOwnership(long userId, long hobbyId) {
    jdbc.update(
        "INSERT INTO user_hobbies(user_id,hobby_id,tree_status) VALUES (?,?,'ACTIVE')",
        userId,
        hobbyId);
  }

  // Scopes the candidate pool to exactly the given hobbies, so leftover/concurrent catalog
  // hobbies elsewhere in the shared test DB can't leak into a test's result assertions.
  private List<Long> excludeAllBut(Long... keep) {
    var keepSet = List.of(keep);
    List<Long> all =
        jdbc.queryForList("SELECT hobby_id FROM hobbies WHERE is_supported=true", Long.class);
    return all.stream().filter(id -> !keepSet.contains(id)).collect(Collectors.toList());
  }

  private String body(Integer preferencesVersion, Integer count, List<Long> excludeHobbyIds) {
    StringBuilder sb = new StringBuilder("{");
    if (preferencesVersion != null) sb.append("\"preferencesVersion\":").append(preferencesVersion);
    if (count != null) sb.append(sb.length() > 1 ? "," : "").append("\"count\":").append(count);
    sb.append(sb.length() > 1 ? "," : "")
        .append("\"excludeHobbyIds\":")
        .append(
            excludeHobbyIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]")));
    sb.append("}");
    return sb.toString();
  }

  private List<String> fieldNames(JsonNode node) {
    return new ArrayList<>(node.propertyNames());
  }

  private HttpResponse<String> create(String token, String idempotencyKey, String requestBody)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> latest(String token) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + "/latest"))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode assertCreatedResult(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    assertThat(code(response)).isEqualTo("RECOMMENDATION201_1");
    return result(response);
  }

  private String code(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("code").asText();
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update(
        "DELETE FROM recommendation_items WHERE recommendation_id IN"
            + " (SELECT recommendation_id FROM recommendations WHERE user_id=?)",
        userId);
    jdbc.update("DELETE FROM recommendations WHERE user_id=?", userId);
    for (Long hobbyId : createdHobbyIds) {
      jdbc.update("DELETE FROM recommendation_items WHERE hobby_id=?", hobbyId);
      jdbc.update("DELETE FROM user_hobbies WHERE hobby_id=?", hobbyId);
      jdbc.update(
          "DELETE FROM activities WHERE hobby_stage_id IN"
              + " (SELECT hobby_stage_id FROM hobby_stages WHERE hobby_id=?)",
          hobbyId);
      jdbc.update("DELETE FROM hobby_stages WHERE hobby_id=?", hobbyId);
      jdbc.update("DELETE FROM hobbies WHERE hobby_id=?", hobbyId);
    }
    super.cleanupSeededHobbies(userId);
  }
}
