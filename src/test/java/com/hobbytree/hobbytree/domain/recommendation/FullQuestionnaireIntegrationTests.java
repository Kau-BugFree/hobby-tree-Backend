package com.hobbytree.hobbytree.domain.recommendation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.onboarding.service.QuestionnaireContract;
import com.hobbytree.hobbytree.domain.recommendation.client.RecommendationAiClient;
import com.hobbytree.hobbytree.domain.recommendation.code.RecommendationErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
    named = "AUTH_INTEGRATION_TEST",
    matches = "true")
class FullQuestionnaireIntegrationTests extends IntegrationTestSupport {
  @MockitoBean RecommendationAiClient ai;
  private long userId;
  private String token;
  private final List<Long> catalogIds = new ArrayList<>();

  @BeforeEach
  void fixtures() throws Exception {
    var signed = result(login());
    userId = signed.path("userId").asLong();
    token = signed.path("accessToken").asText();
    createdUsers.add(userId);
    jdbc.update("UPDATE users SET nickname='설문통합테스트' WHERE user_id=?", userId);
    for (String code : List.of("reading", "cooking", "instrument")) {
      catalogIds.add(
          jdbc.queryForObject(
              "INSERT INTO hobbies(hobby_code,hobby_name,category,indoor_outdoor) VALUES (?,?,?,'INDOOR') RETURNING hobby_id",
              Long.class,
              code,
              code,
              code.toUpperCase(Locale.ROOT)));
    }
    when(ai.recommend(any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(((JsonNode) call.getArgument(0)).path("top_k").asInt())
                  .isGreaterThanOrEqualTo(3);
              return fixture("response");
            });
  }

  @Test
  void savesAllAnswersCompletesOnboardingAndReplaysTheSameRecommendation() throws Exception {
    var input = questionnaire();
    var saved = save(input, 0);
    assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
    assertThat(result(saved).path("questionnaire")).isEqualTo(input);
    assertThat(result(saved).path("availableTimeMinutes").isNull()).isTrue();
    assertThat(result(save(input, 1)).path("preferencesVersion").asInt()).isEqualTo(1);
    assertThat(
            result(http("GET", "/api/v1/users/me/preferences", null, null)).path("questionnaire"))
        .isEqualTo(input);
    assertThat(create("pending", 1, List.of()).statusCode()).isEqualTo(403);
    verifyNoInteractions(ai);
    complete();
    var created = create("stable-key", 1, List.of());
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    assertThat(result(created).path("items")).hasSize(3);
    assertThat(result(created).path("items").get(0).path("hobbyId").asLong())
        .isEqualTo(catalogIds.getFirst());
    assertThat(result(created).path("items").get(0).path("score").asDouble()).isEqualTo(100);
    assertThat(result(created).path("engineResult").path("status").asText())
        .isEqualTo("recommended");
    assertThat(result(create("stable-key", 1, List.of()))).isEqualTo(result(created));
    verify(ai, times(1)).recommend(any());
    assertThat(count("recommendations")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM recommendation_items WHERE first_activity_id IS NOT NULL",
                Integer.class))
        .isZero();
    var latest = http("GET", "/api/v1/recommendations/latest", null, null);
    assertThat(latest.statusCode()).as(latest.body()).isEqualTo(200);
    assertThat(result(latest).path("engineResult")).isEqualTo(result(created).path("engineResult"));
    input.putObject("resources").put("smartphone", false);
    assertThat(result(save(input, 1)).path("preferencesVersion").asInt()).isEqualTo(2);
    assertThat(
            result(http("GET", "/api/v1/recommendations/latest", null, null))
                .path("isStale")
                .asBoolean())
        .isTrue();
  }

  @Test
  void filtersOwnedAndExcludedBeforeCountAndKeepsEmptyLatestResult() throws Exception {
    ready(questionnaire());
    jdbc.update(
        "INSERT INTO user_hobbies(user_id,hobby_id,tree_status) VALUES (?,?,'ACTIVE')",
        userId,
        catalogIds.getFirst());
    var first =
        http("POST", "/api/v1/recommendations", "{\"preferencesVersion\":1,\"count\":1}", "owned");
    assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
    assertThat(result(first).path("items")).hasSize(1);
    assertThat(result(first).path("items").get(0).path("hobbyCode").asText()).isEqualTo("cooking");
    assertThat(result(first).path("engineResult").path("follow_up_questions"))
        .isEqualTo(
            result(first)
                .path("engineResult")
                .path("recommendations")
                .get(0)
                .path("activities")
                .get(0)
                .path("confirmation_required"));
    var empty = create("excluded", 1, List.of(catalogIds.get(1), catalogIds.get(2)));
    assertThat(empty.statusCode()).as(empty.body()).isEqualTo(201);
    assertThat(result(empty).path("engineResult").path("status").asText()).isEqualTo("no_match");
    assertThat(result(empty).path("items")).isEmpty();
    assertThat(result(empty).path("engineResult").path("follow_up_questions")).isEmpty();
    assertThat(http("GET", "/api/v1/recommendations/latest", null, null).statusCode())
        .isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(strings = {"browse", "needs_review", "no_match"})
  void persistsNonRecommendedStatesWithoutCreatingFakeItems(String status) throws Exception {
    var request = questionnaire();
    if (status.equals("browse")) ((ObjectNode) request.path("answers")).put("q11", "안내 없이 직접 찾기");
    if (status.equals("needs_review")) request.put("context_text", "손목 상태를 추가로 확인하고 싶어요");
    var response = fixture("response").put("status", status);
    response.putArray("recommendations");
    response.putArray("excluded");
    doReturn(response).when(ai).recommend(any());
    ready(request);
    var created = create(status, 1, List.of());
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    assertThat(result(created).path("engineResult").path("status").asText()).isEqualTo(status);
    assertThat(result(created).path("items")).isEmpty();
    assertThat(
            result(http("GET", "/api/v1/recommendations/latest", null, null))
                .path("engineResult")
                .path("status")
                .asText())
        .isEqualTo(status);
  }

  @Test
  void outageReleasesClaimAndNeverFallsBackToUnrelatedLegacyProfile() throws Exception {
    ready(questionnaire());
    doThrow(new ProjectException(RecommendationErrorCode.AI_UNAVAILABLE)).when(ai).recommend(any());
    assertThat(create("retry", 1, List.of()).statusCode()).isEqualTo(503);
    assertThat(count("recommendations")).isZero();
    doReturn(fixture("response")).when(ai).recommend(any());
    assertThat(create("retry", 1, List.of()).statusCode()).isEqualTo(201);
  }

  @Test
  void missingDatabaseCodeFailsWithoutPartialResultAndCanBeRetried() throws Exception {
    ready(questionnaire());
    jdbc.update(
        "UPDATE hobbies SET hobby_code='test-missing-reading' WHERE hobby_id=?",
        catalogIds.getFirst());
    assertThat(create("missing", 1, List.of()).statusCode()).isEqualTo(502);
    assertThat(count("recommendations")).isZero();
    jdbc.update("UPDATE hobbies SET hobby_code='reading' WHERE hobby_id=?", catalogIds.getFirst());
    assertThat(create("missing", 1, List.of()).statusCode()).isEqualTo(201);
  }

  @ParameterizedTest
  @ValueSource(strings = {"preference", "SUSPENDED", "WITHDRAWN"})
  void rejectsStateChangedWhileUpstreamRunsAndSerializesDuplicateClaims(String change)
      throws Exception {
    ready(questionnaire());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              entered.countDown();
              assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
              return fixture("response");
            })
        .when(ai)
        .recommend(any());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = executor.submit(() -> create("race", 1, List.of()));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(create("race", 1, List.of()).statusCode()).isEqualTo(409);
        if (change.equals("preference")) {
          var changed = questionnaire().put("monthly_committed_krw", 15000);
          assertThat(save(changed, 1).statusCode()).isEqualTo(200);
        } else if (change.equals("WITHDRAWN")) {
          jdbc.update(
              "UPDATE users SET account_status='WITHDRAWN',withdrawn_at=now(),restorable_until=now()+interval '720 hours',status_before_withdrawal='ACTIVE' WHERE user_id=?",
              userId);
        } else jdbc.update("UPDATE users SET account_status=? WHERE user_id=?", change, userId);
      } finally {
        release.countDown();
      }
      assertThat(pending.get(15, TimeUnit.SECONDS).statusCode())
          .isEqualTo(change.equals("preference") ? 409 : 403);
    }
    assertThat(count("recommendations")).isZero();
    verify(ai, times(1)).recommend(any());
  }

  @Test
  void anotherUserCannotSeeRecommendationOrReplayTheFirstUsersKey() throws Exception {
    ready(questionnaire());
    assertThat(create("shared-key", 1, List.of()).statusCode()).isEqualTo(201);
    socialId = "full14-other-" + UUID.randomUUID();
    when(verifier.verify(any(), anyString())).thenReturn(socialId);
    var second = result(login());
    long other = second.path("userId").asLong();
    createdUsers.add(other);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", other);
    token = second.path("accessToken").asText();
    assertThat(http("GET", "/api/v1/recommendations/latest", null, null).statusCode())
        .isEqualTo(404);
    assertThat(save(questionnaire(), 0).statusCode()).isEqualTo(200);
    var own = create("shared-key", 1, List.of());
    assertThat(own.statusCode()).as(own.body()).isEqualTo(201);
    verify(ai, times(2)).recommend(any());
  }

  private int count(String table) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE user_id=?", Integer.class, userId);
  }

  @Test
  void preservesVersionForKeyOrderAndSupportsExplicitLegacyModeSwitch() throws Exception {
    var input = questionnaire();
    assertThat(save(input, 0).statusCode()).isEqualTo(200);
    var reordered = mapper.createObjectNode();
    var fields = new ArrayList<>(input.propertyNames());
    Collections.reverse(fields);
    fields.forEach(key -> reordered.set(key, input.get(key)));
    assertThat(result(save(reordered, 1)).path("preferencesVersion").asInt()).isEqualTo(1);
    assertThat(save(reordered, 0).statusCode()).isEqualTo(409);
    var mixed =
        mapper
            .createObjectNode()
            .put("optionsVersion", QuestionnaireContract.VERSION)
            .put("expectedVersion", 1)
            .put("budgetLevel", "LOW");
    mixed.set("questionnaire", input);
    assertThat(
            http("PUT", "/api/v1/users/me/preferences", mapper.writeValueAsString(mixed), null)
                .statusCode())
        .isEqualTo(400);
    var legacy =
        http(
            "PUT",
            "/api/v1/users/me/preferences",
            """
        {"optionsVersion":"2026-11-07","budgetLevel":"LOW","availableTimeMinutes":30,
         "indoorOutdoor":"INDOOR","difficultyPreference":"BEGINNER","preferredCategoryIds":[1],"expectedVersion":1}
        """,
            null);
    assertThat(legacy.statusCode()).as(legacy.body()).isEqualTo(200);
    assertThat(result(legacy).path("preferencesVersion").asInt()).isEqualTo(2);
    assertThat(result(legacy).has("questionnaire")).isFalse();
    assertThat(result(save(input, 2)).path("preferencesVersion").asInt()).isEqualTo(3);
    verifyNoInteractions(ai);
  }

  @Test
  void latestRemovesHiddenHobbiesAndStandalonePersistenceClaim() throws Exception {
    var upstream = fixture("response");
    ((tools.jackson.databind.node.ArrayNode) upstream.path("notices"))
        .add("입력은 이 응답에 반환하며 서버에는 저장하지 않습니다.");
    doReturn(upstream).when(ai).recommend(any());
    ready(questionnaire());
    var created = create("hidden", 1, List.of());
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    assertThat(result(created).path("engineResult").path("notices").toString())
        .doesNotContain("저장하지 않습니다");
    for (long id : catalogIds)
      jdbc.update("UPDATE hobbies SET is_supported=false WHERE hobby_id=?", id);
    var latest = http("GET", "/api/v1/recommendations/latest", null, null);
    assertThat(latest.statusCode()).as(latest.body()).isEqualTo(200);
    assertThat(result(latest).path("items")).isEmpty();
    assertThat(result(latest).path("engineResult").path("status").asText()).isEqualTo("no_match");
    assertThat(result(latest).path("engineResult").path("follow_up_questions")).isEmpty();
  }

  private void ready(ObjectNode request) throws Exception {
    var saved = save(request, 0);
    assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
    complete();
  }

  private void complete() throws Exception {
    var completed =
        http(
            "POST",
            "/api/v1/onboarding/complete",
            """
      {"preferencesVersion":1,"agreements":[
       {"termsId":1,"version":"2.0","agreed":true},
       {"termsId":5,"version":"1.0","agreed":true},
       {"termsId":2,"version":"2.0","agreed":true},
       {"termsId":3,"version":"1.0","agreed":true}]}
      """,
            null);
    assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
  }

  private HttpResponse<String> save(ObjectNode request, int expected) throws Exception {
    var body =
        mapper
            .createObjectNode()
            .put("optionsVersion", QuestionnaireContract.VERSION)
            .put("expectedVersion", expected);
    body.set("questionnaire", request);
    return http("PUT", "/api/v1/users/me/preferences", mapper.writeValueAsString(body), null);
  }

  private HttpResponse<String> create(String key, int version, List<Long> excluded)
      throws Exception {
    return http(
        "POST",
        "/api/v1/recommendations",
        mapper.writeValueAsString(
            Map.of("preferencesVersion", version, "count", 3, "excludeHobbyIds", excluded)),
        key);
  }

  private HttpResponse<String> http(String method, String path, String body, String key)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (key != null) builder.header("Idempotency-Key", key);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private ObjectNode questionnaire() throws Exception {
    var input = fixture("request");
    input.remove("top_k");
    return input;
  }

  private ObjectNode fixture(String name) throws Exception {
    try (var input =
        new ClassPathResource("recommendation-ai/" + name + ".json").getInputStream()) {
      return (ObjectNode) mapper.readTree(input);
    }
  }

  @Override
  protected void cleanupSeededHobbies(long id) {
    jdbc.update("DELETE FROM recommendations WHERE user_id=?", id);
    super.cleanupSeededHobbies(id);
    // All users are cleaned before deleting the shared fixture catalogue in the final iteration.
    if (createdUsers.stream()
        .allMatch(
            user ->
                jdbc.queryForObject(
                        "SELECT count(*) FROM recommendations WHERE user_id=?", Integer.class, user)
                    == 0)) {
      for (long hobby : catalogIds) {
        jdbc.update("DELETE FROM user_hobbies WHERE hobby_id=?", hobby);
        jdbc.update("DELETE FROM hobbies WHERE hobby_id=?", hobby);
      }
    }
  }
}
