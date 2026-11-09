package com.hobbytree.hobbytree.domain.coach;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class CoachIntegrationTests extends IntegrationTestSupport {
  private static final String PATH = "/api/v1/coach";
  private final java.util.Map<Long, String> tokensByUserId = new java.util.HashMap<>();

  @Test
  void startsNewConversationAndReturnsTheExactContract() throws Exception {
    long userId = activeUser();

    var response = chat(token(userId), UUID.randomUUID().toString(), body(null, "안녕 코치", null));
    var result = assertOkResult(response);

    assertThat(fieldNames(result))
        .containsExactlyInAnyOrder(
            "conversationId",
            "messageId",
            "answer",
            "references",
            "suggestedActivities",
            "usedContext",
            "createdAt");
    assertThat(result.path("conversationId").asLong()).isPositive();
    assertThat(result.path("messageId").asLong()).isPositive();
    assertThat(result.path("answer").asText()).isNotBlank();
    assertThat(result.path("references")).isEmpty();
    assertThat(result.path("suggestedActivities")).isEmpty();
    assertThat(result.path("usedContext").isNull()).isTrue();
    assertThat(result.path("createdAt").asText()).isNotBlank();

    long conversationId = result.path("conversationId").asLong();
    assertThat(
            jdbc.queryForObject(
                "SELECT user_id FROM coach_conversations WHERE conversation_id=?",
                Long.class,
                conversationId))
        .isEqualTo(userId);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM coach_messages WHERE conversation_id=?",
                Integer.class,
                conversationId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT role FROM coach_messages WHERE conversation_id=? ORDER BY message_id ASC"
                    + " LIMIT 1",
                String.class,
                conversationId))
        .isEqualTo("USER");
    assertThat(
            jdbc.queryForObject(
                "SELECT answer_version FROM coach_messages WHERE conversation_id=? AND"
                    + " role='ASSISTANT'",
                String.class,
                conversationId))
        .isEqualTo("PART1_PLACEHOLDER");
  }

  @Test
  void continuesAnExistingConversationAndIgnoresUserHobbyIdOnContinuation() throws Exception {
    long userId = activeUser();
    long hobbyId = hobby(userId);
    long userHobbyId = plantTree(userId, hobbyId);

    var first =
        assertOkResult(chat(token(userId), UUID.randomUUID().toString(), body(null, "첫 질문", null)));
    long conversationId = first.path("conversationId").asLong();

    var second =
        assertOkResult(
            chat(
                token(userId),
                UUID.randomUUID().toString(),
                body(conversationId, "두번째 질문", userHobbyId)));

    assertThat(second.path("conversationId").asLong()).isEqualTo(conversationId);
    // userHobbyId on a continuing request is ignored - context stays whatever the conversation
    // started with (none, here), not whatever this later message happens to send.
    assertThat(second.path("usedContext").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM coach_messages WHERE conversation_id=?",
                Integer.class,
                conversationId))
        .isEqualTo(4);
  }

  @Test
  void includesUsedContextWhenAnOwnedUserHobbyIdIsGiven() throws Exception {
    long userId = activeUser();
    long hobbyId = hobby(userId);
    long userHobbyId = plantTree(userId, hobbyId);

    var result =
        assertOkResult(
            chat(
                token(userId),
                UUID.randomUUID().toString(),
                body(null, "오늘 뭐 하면 좋을까?", userHobbyId)));

    assertThat(result.path("usedContext").path("userHobbyId").asLong()).isEqualTo(userHobbyId);
    assertThat(result.path("usedContext").path("hobbyId").asLong()).isEqualTo(hobbyId);
  }

  @Test
  void rejectsAUserHobbyIdNotOwnedByTheCaller() throws Exception {
    long userId = activeUser();
    long otherUserId = otherUser();
    long hobbyId = hobby(otherUserId);
    long someoneElsesUserHobbyId = plantTree(otherUserId, hobbyId);

    var response =
        chat(
            token(userId),
            UUID.randomUUID().toString(),
            body(null, "질문입니다", someoneElsesUserHobbyId));
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(code(response)).isEqualTo("TREE404_1");
  }

  @Test
  void rejectsContinuingAnUnknownOrSomeoneElsesConversation() throws Exception {
    long userId = activeUser();
    long otherUserId = otherUser();
    long someoneElsesConversationId = seedConversation(otherUserId);

    var response =
        chat(
            token(userId),
            UUID.randomUUID().toString(),
            body(someoneElsesConversationId, "내 질문", null));
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(code(response)).isEqualTo("COACH404_1");

    var missing = chat(token(userId), UUID.randomUUID().toString(), body(999_999_999L, "질문", null));
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(code(missing)).isEqualTo("COACH404_1");
  }

  @Test
  void rejectsBlankAndTooLongMessages() throws Exception {
    long userId = activeUser();
    String tooLong = "a".repeat(2001);
    for (String invalidBody :
        List.of(body(null, "", null), body(null, "   ", null), body(null, tooLong, null))) {
      var response = chat(token(userId), UUID.randomUUID().toString(), invalidBody);
      assertThat(response.statusCode()).as(invalidBody).isEqualTo(400);
      assertThat(code(response)).as(invalidBody).isEqualTo("COACH400_1");
    }
  }

  @Test
  void requiresTheIdempotencyKeyHeader() throws Exception {
    long userId = activeUser();
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + "/chat"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token(userId))
            .POST(HttpRequest.BodyPublishers.ofString(body(null, "질문", null)));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(code(response)).isEqualTo("COMMON400_1");
  }

  @Test
  void replaysTheSameResultForARetryWithTheSameIdempotencyKeyAndBody() throws Exception {
    long userId = activeUser();
    String key = UUID.randomUUID().toString();
    String requestBody = body(null, "재시도 테스트", null);

    var first = assertOkResult(chat(token(userId), key, requestBody));
    var second = assertOkResult(chat(token(userId), key, requestBody));

    assertThat(second.path("conversationId").asLong())
        .isEqualTo(first.path("conversationId").asLong());
    assertThat(second.path("messageId").asLong()).isEqualTo(first.path("messageId").asLong());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM coach_conversations WHERE user_id=?", Integer.class, userId))
        .isEqualTo(1);
  }

  @Test
  void rejectsReusingTheSameIdempotencyKeyWithADifferentBody() throws Exception {
    long userId = activeUser();
    String key = UUID.randomUUID().toString();

    assertOkResult(chat(token(userId), key, body(null, "첫 질문", null)));
    var conflict = chat(token(userId), key, body(null, "다른 질문", null));
    assertThat(conflict.statusCode()).isEqualTo(409);
    assertThat(code(conflict)).isEqualTo("COMMON409_1");
  }

  @Test
  void rejectsPendingOnboarding() throws Exception {
    var signedIn = result(login());
    long userId = signedIn.path("userId").asLong();
    createdUsers.add(userId);
    var response =
        chat(
            signedIn.path("accessToken").asText(),
            UUID.randomUUID().toString(),
            body(null, "질문", null));
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(code(response)).isEqualTo("AUTH403_2");
  }

  @Test
  void requiresAuthentication() throws Exception {
    assertThat(chat(null, UUID.randomUUID().toString(), body(null, "질문", null)).statusCode())
        .isEqualTo(401);
  }

  @Test
  void listsConversationsOrderedByLastMessageAtDescWithAPreview() throws Exception {
    long userId = activeUser();
    var older =
        assertOkResult(
            chat(token(userId), UUID.randomUUID().toString(), body(null, "오래된 대화", null)));
    var newer =
        assertOkResult(
            chat(token(userId), UUID.randomUUID().toString(), body(null, "최근 대화", null)));

    var result = result(conversationsList(token(userId), null, null));
    assertThat(result.path("items")).hasSize(2);
    assertThat(result.path("items").get(0).path("conversationId").asLong())
        .isEqualTo(newer.path("conversationId").asLong());
    assertThat(result.path("items").get(0).path("lastMessagePreview").asText())
        .isEqualTo("하비 코치의 AI 연동은 아직 준비 중입니다. 질문은 저장되었고, AI 연동이 완료되면 실제 답변을 받아볼 수 있어요.");
    assertThat(result.path("items").get(1).path("conversationId").asLong())
        .isEqualTo(older.path("conversationId").asLong());
    assertThat(result.path("hasNext").asBoolean()).isFalse();
  }

  @Test
  void listsMessagesInAConversationOrderedAscending() throws Exception {
    long userId = activeUser();
    var created =
        assertOkResult(chat(token(userId), UUID.randomUUID().toString(), body(null, "첫 질문", null)));
    long conversationId = created.path("conversationId").asLong();
    chat(token(userId), UUID.randomUUID().toString(), body(conversationId, "두번째 질문", null));

    var result = result(conversationMessages(token(userId), conversationId, null, null));
    assertThat(result.path("conversationId").asLong()).isEqualTo(conversationId);
    assertThat(result.path("items")).hasSize(4);
    assertThat(result.path("items").get(0).path("role").asText()).isEqualTo("USER");
    assertThat(result.path("items").get(0).path("content").asText()).isEqualTo("첫 질문");
    assertThat(result.path("items").get(2).path("content").asText()).isEqualTo("두번째 질문");
    assertThat(result.path("hasNext").asBoolean()).isFalse();
  }

  @Test
  void rejectsListingMessagesForSomeoneElsesConversation() throws Exception {
    long userId = activeUser();
    long otherUserId = otherUser();
    long conversationId = seedConversation(otherUserId);

    var response = conversationMessages(token(userId), conversationId, null, null);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(code(response)).isEqualTo("COACH404_1");
  }

  // --- helpers -----------------------------------------------------------

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

  private long hobby(long userId) {
    return jdbc.queryForObject(
        "INSERT INTO hobbies(hobby_code,hobby_name,category,indoor_outdoor) VALUES"
            + " (?,?,?,?) RETURNING hobby_id",
        Long.class,
        "profile-test-" + userId + "-" + UUID.randomUUID().toString().substring(0, 12),
        "코치 테스트 " + UUID.randomUUID(),
        "COOKING",
        "INDOOR");
  }

  // A second real user that never logs in via HTTP - calling login() twice in one test would
  // mint the same account, since the social verifier mock returns one fixed socialId per test.
  private long otherUser() {
    long id =
        jdbc.queryForObject(
            "INSERT INTO users(provider,provider_user_id,account_status) VALUES"
                + " ('KAKAO',?,'ACTIVE') RETURNING user_id",
            Long.class,
            UUID.randomUUID().toString());
    createdUsers.add(id);
    return id;
  }

  private long seedConversation(long userId) {
    return jdbc.queryForObject(
        "INSERT INTO coach_conversations(user_id) VALUES (?) RETURNING conversation_id",
        Long.class,
        userId);
  }

  private long plantTree(long userId, long hobbyId) {
    return jdbc.queryForObject(
        "INSERT INTO user_hobbies(user_id,hobby_id,tree_status) VALUES (?,?,'ACTIVE') RETURNING"
            + " user_hobby_id",
        Long.class,
        userId,
        hobbyId);
  }

  private List<String> fieldNames(JsonNode node) {
    return new java.util.ArrayList<>(node.propertyNames());
  }

  private String body(Long conversationId, String message, Long userHobbyId) {
    StringBuilder sb = new StringBuilder("{");
    if (conversationId != null) sb.append("\"conversationId\":").append(conversationId).append(",");
    sb.append("\"message\":").append(mapper.writeValueAsString(message));
    if (userHobbyId != null) sb.append(",\"userHobbyId\":").append(userHobbyId);
    sb.append("}");
    return sb.toString();
  }

  private HttpResponse<String> chat(String token, String idempotencyKey, String requestBody)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + "/chat"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> conversationsList(String token, String cursor, Integer size)
      throws Exception {
    StringBuilder query = new StringBuilder();
    if (cursor != null) query.append(query.isEmpty() ? "?" : "&").append("cursor=").append(cursor);
    if (size != null) query.append(query.isEmpty() ? "?" : "&").append("size=").append(size);
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + PATH + "/conversations" + query))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> conversationMessages(
      String token, long conversationId, String cursor, Integer size) throws Exception {
    StringBuilder query = new StringBuilder();
    if (cursor != null) query.append(query.isEmpty() ? "?" : "&").append("cursor=").append(cursor);
    if (size != null) query.append(query.isEmpty() ? "?" : "&").append("size=").append(size);
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + PATH
                        + "/conversations/"
                        + conversationId
                        + "/messages"
                        + query))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode assertOkResult(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(code(response)).isEqualTo("COACH200_1");
    return result(response);
  }

  private String code(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("code").asText();
  }
}
