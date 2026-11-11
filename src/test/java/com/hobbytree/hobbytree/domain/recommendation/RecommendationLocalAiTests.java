package com.hobbytree.hobbytree.domain.recommendation;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.onboarding.service.QuestionnaireContract;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Opt-in test for the pinned, network-isolated FastAPI container; never a paid model endpoint. */
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "RECOMMENDATION_LOCAL_AI_TEST", matches = "true")
class RecommendationLocalAiTests extends IntegrationTestSupport {
  private final List<Long> catalog = new ArrayList<>();

  @ParameterizedTest
  @ValueSource(strings = {"recommended", "browse", "needs_review", "no_match"})
  void savesQuestionnaireAndCallsRealFastApiWithAllFourStates(String state) throws Exception {
    var signed = result(login());
    long userId = signed.path("userId").asLong();
    createdUsers.add(userId);
    String token = signed.path("accessToken").asText();
    for (String code : List.of("reading", "cooking", "instrument")) {
      catalog.add(
          jdbc.queryForObject(
              "INSERT INTO hobbies(hobby_code,hobby_name,category,indoor_outdoor) VALUES (?,?,?,'INDOOR') RETURNING hobby_id",
              Long.class,
              code,
              code,
              code.toUpperCase(Locale.ROOT)));
    }
    ObjectNode request;
    try (var input = new ClassPathResource("recommendation-ai/request.json").getInputStream()) {
      request = (ObjectNode) mapper.readTree(input);
    }
    request.remove("top_k");
    if (state.equals("browse")) ((ObjectNode) request.path("answers")).put("q11", "안내 없이 직접 찾기");
    if (state.equals("needs_review")) request.put("context_text", "추가 조건 확인이 필요해요");
    if (state.equals("no_match")) {
      var resources = request.putObject("resources");
      new QuestionnaireContract(mapper)
          .options()
          .path("resources")
          .propertyNames()
          .forEach(key -> resources.put(key, false));
    }
    var body =
        mapper
            .createObjectNode()
            .put("optionsVersion", QuestionnaireContract.VERSION)
            .put("expectedVersion", 0);
    body.set("questionnaire", request);
    var saved = http(token, "PUT", "/api/v1/users/me/preferences", body, null);
    assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", userId);
    var create = mapper.createObjectNode().put("preferencesVersion", 1).put("count", 3);
    var response = http(token, "POST", "/api/v1/recommendations", create, "local-real");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    assertThat(result(response).path("engineResult").path("status").asText()).isEqualTo(state);
    assertThat(result(response).path("engineResult").path("rules_version").asText())
        .isEqualTo("rules-2.0.0");
    var latest = http(token, "GET", "/api/v1/recommendations/latest", null, null);
    assertThat(latest.statusCode()).as(latest.body()).isEqualTo(200);
    assertThat(result(latest).path("engineResult"))
        .isEqualTo(result(response).path("engineResult"));
    assertThat(result(http(token, "POST", "/api/v1/recommendations", create, "local-real")))
        .isEqualTo(result(response));
  }

  private HttpResponse<String> http(
      String token, String method, String path, JsonNode body, String key) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
    if (key != null) request.header("Idempotency-Key", key);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update("DELETE FROM recommendations WHERE user_id=?", userId);
    super.cleanupSeededHobbies(userId);
    for (long id : catalog) jdbc.update("DELETE FROM hobbies WHERE hobby_id=?", id);
  }
}
