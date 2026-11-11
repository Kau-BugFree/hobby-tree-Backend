package com.hobbytree.hobbytree.domain.coach;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

// Explicit opt-in only: isolated Spring/PG/Redis + offline local FastAPI with the cooking
// catalog loaded. Ordinary CI uses the deterministic HTTP/unit and integration suites instead.
@EnabledIfEnvironmentVariable(named = "COACH_LOCAL_AI_TEST", matches = "true")
class CoachLocalAiTests extends IntegrationTestSupport {
  @Test
  void realFastApiRoundTripPersistsHistoryReplaysAndSeparatesUsers() throws Exception {
    var first = activeLogin();
    String firstToken = first.path("accessToken").asText();
    String key = UUID.randomUUID().toString();
    var initial = chat(firstToken, key, null, "오늘 20분 안에 할 요리 활동 추천해줘");
    long conversation = initial.path("conversationId").asLong();
    assertThat(initial.path("answer").asText()).isNotBlank().doesNotContain("아직 준비 중");
    assertThat(initial.path("suggestedActivities")).isNotEmpty();
    assertThat(chat(firstToken, key, null, "오늘 20분 안에 할 요리 활동 추천해줘")).isEqualTo(initial);
    var followup = chat(firstToken, UUID.randomUUID().toString(), conversation, "첫 번째 걸로 할게");
    assertThat(followup.path("conversationId").asLong()).isEqualTo(conversation);
    assertThat(followup.path("answer").asText()).isNotBlank();
    var history = get(firstToken, conversation);
    assertThat(history.statusCode()).isEqualTo(200);
    assertThat(result(history).path("items")).hasSize(4);
    assertThat(result(history).path("items").get(1).path("references"))
        .isEqualTo(initial.path("references"));

    var second = activeLogin();
    String secondToken = second.path("accessToken").asText();
    assertThat(get(secondToken, conversation).statusCode()).isEqualTo(404);
    var other = chat(secondToken, UUID.randomUUID().toString(), null, "간장 드레싱 만드는 활동 추천해줘");
    assertThat(other.path("conversationId").asLong()).isNotEqualTo(conversation);
    assertThat(
            jdbc.queryForObject(
                "SELECT answer_version FROM coach_messages WHERE message_id=?",
                String.class,
                other.path("messageId").asLong()))
        .isEqualTo("coach-v3.0.0");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_records r JOIN user_hobbies h USING(user_hobby_id) WHERE h.user_id IN (?,?)",
                Integer.class,
                first.path("userId").asLong(),
                second.path("userId").asLong()))
        .isZero();
  }

  private JsonNode activeLogin() throws Exception {
    when(verifier.verify(any(), anyString())).thenReturn("local-ai-" + UUID.randomUUID());
    var response = login();
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var user = result(response);
    long id = user.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return user;
  }

  private JsonNode chat(String token, String key, Long conversation, String message)
      throws Exception {
    var body = new HashMap<String, Object>();
    body.put("conversationId", conversation);
    body.put("message", message);
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/coach/chat"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return result(response);
  }

  private HttpResponse<String> get(String token, long conversation) throws Exception {
    return client.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/v1/coach/conversations/"
                        + conversation
                        + "/messages"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
