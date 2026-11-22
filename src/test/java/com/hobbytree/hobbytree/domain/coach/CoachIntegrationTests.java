package com.hobbytree.hobbytree.domain.coach;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.coach.client.*;
import com.hobbytree.hobbytree.domain.coach.code.CoachErrorCode;
import com.hobbytree.hobbytree.domain.coach.service.CoachService;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyService;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class CoachIntegrationTests extends IntegrationTestSupport {
  @MockitoBean CoachAiClient ai;
  @MockitoSpyBean IdempotencyKeyService keys;
  @Autowired CoachService service;
  private Long createdCookingHobby;

  @BeforeEach
  void aiReply() {
    when(ai.chat(notNull()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return reply(call.getArgument(0));
            });
  }

  private CoachAiResponse reply(CoachAiRequest request) {
    return new CoachAiResponse(
        request.conversationId(),
        "요리 자료를 안내합니다.",
        "coach-v3.0.0",
        "browse",
        List.of(),
        List.of(),
        false);
  }

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
        .isEqualTo("coach-v3.0.0");
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
        .isEqualTo("요리 자료를 안내합니다.");
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

  @Test
  void conversationListCursorPaginationHasNoDuplicatesOrGapsAcrossPages() throws Exception {
    long userId = activeUser();
    long first =
        assertOkResult(chat(token(userId), UUID.randomUUID().toString(), body(null, "대화 1", null)))
            .path("conversationId")
            .asLong();
    long second =
        assertOkResult(chat(token(userId), UUID.randomUUID().toString(), body(null, "대화 2", null)))
            .path("conversationId")
            .asLong();
    long third =
        assertOkResult(chat(token(userId), UUID.randomUUID().toString(), body(null, "대화 3", null)))
            .path("conversationId")
            .asLong();

    var firstPage = result(conversationsList(token(userId), null, 2));
    assertThat(firstPage.path("items")).hasSize(2);
    assertThat(firstPage.path("hasNext").asBoolean()).isTrue();
    String cursor = firstPage.path("nextCursor").asText();
    assertThat(cursor).isNotBlank();

    var secondPage = result(conversationsList(token(userId), cursor, 2));
    assertThat(secondPage.path("items")).hasSize(1);
    assertThat(secondPage.path("hasNext").asBoolean()).isFalse();
    assertThat(secondPage.path("nextCursor").isNull()).isTrue();

    List<Long> allIds = new ArrayList<>();
    firstPage.path("items").forEach(n -> allIds.add(n.path("conversationId").asLong()));
    secondPage.path("items").forEach(n -> allIds.add(n.path("conversationId").asLong()));
    // Newest first, no duplicate and no gap across the two pages.
    assertThat(allIds).containsExactly(third, second, first);
  }

  @Test
  void conversationListTieBreaksByConversationIdDescWhenLastMessageAtMatches() throws Exception {
    long userId = activeUser();
    Instant same = Instant.parse("2026-01-01T00:00:00Z");
    long lower = seedConversationAt(userId, same);
    long higher = seedConversationAt(userId, same);

    var result = result(conversationsList(token(userId), null, null));
    assertThat(result.path("items").get(0).path("conversationId").asLong()).isEqualTo(higher);
    assertThat(result.path("items").get(1).path("conversationId").asLong()).isEqualTo(lower);
  }

  @Test
  void rejectsInvalidCursorAndOutOfRangeSizeForConversationsList() throws Exception {
    long userId = activeUser();
    for (var response :
        List.of(
            conversationsList(token(userId), "zzz", null),
            conversationsList(token(userId), null, 0),
            conversationsList(token(userId), null, 51))) {
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(code(response)).isEqualTo("COMMON400_1");
    }
  }

  @Test
  void messageListCursorPaginationHasNoDuplicatesOrGapsAcrossPages() throws Exception {
    long userId = activeUser();
    long conversationId =
        assertOkResult(chat(token(userId), UUID.randomUUID().toString(), body(null, "턴 1", null)))
            .path("conversationId")
            .asLong();
    chat(token(userId), UUID.randomUUID().toString(), body(conversationId, "턴 2", null));
    chat(token(userId), UUID.randomUUID().toString(), body(conversationId, "턴 3", null));
    // 3 turns = 6 messages (USER+ASSISTANT each).

    var firstPage = result(conversationMessages(token(userId), conversationId, null, 4));
    assertThat(firstPage.path("items")).hasSize(4);
    assertThat(firstPage.path("hasNext").asBoolean()).isTrue();
    String cursor = firstPage.path("nextCursor").asText();
    assertThat(cursor).isNotBlank();

    var secondPage = result(conversationMessages(token(userId), conversationId, cursor, 4));
    assertThat(secondPage.path("items")).hasSize(2);
    assertThat(secondPage.path("hasNext").asBoolean()).isFalse();

    List<Long> allIds = new ArrayList<>();
    firstPage.path("items").forEach(n -> allIds.add(n.path("messageId").asLong()));
    secondPage.path("items").forEach(n -> allIds.add(n.path("messageId").asLong()));
    assertThat(allIds).hasSize(6).doesNotHaveDuplicates();
    for (int i = 1; i < allIds.size(); i++) {
      assertThat(allIds.get(i)).isGreaterThan(allIds.get(i - 1));
    }
  }

  @Test
  void rejectsInvalidCursorAndOutOfRangeSizeForMessagesList() throws Exception {
    long userId = activeUser();
    long conversationId =
        assertOkResult(chat(token(userId), UUID.randomUUID().toString(), body(null, "질문", null)))
            .path("conversationId")
            .asLong();

    for (var response :
        List.of(
            conversationMessages(token(userId), conversationId, "zzz", null),
            conversationMessages(token(userId), conversationId, null, 0),
            conversationMessages(token(userId), conversationId, null, 51))) {
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(code(response)).isEqualTo("COMMON400_1");
    }
  }

  /**
   * A failed first turn remains recoverable under the same identifiers without an empty list item.
   */
  @Test
  void timeoutRetryReusesFirstConversationAndRequestIdAndHidesEmptyReservation() throws Exception {
    long userId = activeUser();
    String key = UUID.randomUUID().toString();
    var seen = new ArrayList<CoachAiRequest>();
    when(ai.chat(any()))
        .thenAnswer(
            call -> {
              CoachAiRequest request = call.getArgument(0);
              seen.add(request);
              if (seen.size() == 1) throw new ProjectException(CoachErrorCode.AI_TIMEOUT);
              return reply(request);
            });
    assertThat(chat(token(userId), key, body(null, "질문", null)).statusCode()).isEqualTo(504);
    assertThat(result(conversationsList(token(userId), null, null)).path("items")).isEmpty();
    var success = assertOkResult(chat(token(userId), key, body(null, "질문", null)));
    assertThat(seen).hasSize(2);
    assertThat(seen.get(0)).isEqualTo(seen.get(1));
    assertThat(success.path("conversationId").asLong()).isEqualTo(seen.get(0).conversationId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM coach_conversations WHERE user_id=?", Integer.class, userId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM coach_messages WHERE conversation_id=?",
                Integer.class,
                seen.get(0).conversationId()))
        .isEqualTo(2);
  }

  /** A failure after completing the key still rolls back the entire final transaction. */
  @Test
  void failedCommitRollsBackBothMessagesAndRetryUsesTheSameAiRequest() throws Exception {
    long userId = activeUser();
    String key = UUID.randomUUID().toString();
    var seen = new ArrayList<CoachAiRequest>();
    when(ai.chat(any()))
        .thenAnswer(
            call -> {
              CoachAiRequest request = call.getArgument(0);
              seen.add(request);
              return reply(request);
            });
    var finishes = new AtomicInteger();
    doAnswer(
            call -> {
              Object completed = call.callRealMethod();
              if (finishes.getAndIncrement() == 0) {
                TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                      @Override
                      public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("isolated commit failure");
                      }
                    });
              }
              return completed;
            })
        .when(keys)
        .complete(anyLong(), anyString(), anyInt(), anyString());
    assertThat(chat(token(userId), key, body(null, "질문", null)).statusCode()).isEqualTo(500);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM coach_messages WHERE conversation_id=?",
                Integer.class,
                seen.get(0).conversationId()))
        .isZero();
    assertOkResult(chat(token(userId), key, body(null, "질문", null)));
    assertThat(seen.get(0)).isEqualTo(seen.get(1));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM idempotency_keys WHERE user_id=? AND idempotency_key=?",
                String.class,
                userId,
                key))
        .isEqualTo("COMPLETED");
  }

  /** A stale worker cannot publish messages after another claim owner has taken over. */
  @Test
  void expiredOwnerCannotWriteMessagesAndReclaimerKeepsReservation() throws Exception {
    long userId = activeUser();
    String key = UUID.randomUUID().toString();
    var seen = new ArrayList<CoachAiRequest>();
    when(ai.chat(any()))
        .thenAnswer(
            call -> {
              CoachAiRequest request = call.getArgument(0);
              seen.add(request);
              if (seen.size() == 1)
                jdbc.update(
                    "UPDATE idempotency_keys SET ownership_token=?, created_at=now()-interval '10 minutes' WHERE user_id=? AND idempotency_key=?",
                    UUID.randomUUID().toString(),
                    userId,
                    key);
              return reply(request);
            });
    assertThat(chat(token(userId), key, body(null, "질문", null)).statusCode()).isEqualTo(409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM coach_messages WHERE conversation_id=?",
                Integer.class,
                seen.get(0).conversationId()))
        .isZero();
    assertOkResult(chat(token(userId), key, body(null, "질문", null)));
    assertThat(seen.get(0)).isEqualTo(seen.get(1));
  }

  @Test
  void parallelTurnsCannotOvertakePendingMessage() throws Exception {
    long userId = activeUser();
    String auth = token(userId);
    long conversationId =
        assertOkResult(chat(auth, UUID.randomUUID().toString(), body(null, "시작", null)))
            .path("conversationId")
            .asLong();
    String key = UUID.randomUUID().toString();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(ai.chat(any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              entered.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
              return reply(call.getArgument(0));
            });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> chat(auth, key, body(conversationId, "첫 질문", null)));
      try {
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(chat(auth, key, body(conversationId, "첫 질문", null)).statusCode()).isEqualTo(409);
        var overtaking =
            chat(auth, UUID.randomUUID().toString(), body(conversationId, "다음 질문", null));
        assertThat(overtaking.statusCode()).isEqualTo(409);
        assertThat(code(overtaking)).isEqualTo("COACH409_1");
      } finally {
        release.countDown();
      }
      assertOkResult(first.get(5, TimeUnit.SECONDS));
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM coach_messages WHERE conversation_id=?",
                Integer.class,
                conversationId))
        .isEqualTo(4);
  }

  @Test
  void rechecksAccountAfterAiResponseBeforeSaving() throws Exception {
    long userId = activeUser();
    when(ai.chat(any()))
        .thenAnswer(
            call -> {
              jdbc.update("UPDATE users SET account_status='SUSPENDED' WHERE user_id=?", userId);
              return reply(call.getArgument(0));
            });
    var response = chat(token(userId), UUID.randomUUID().toString(), body(null, "질문", null));
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(code(response)).isEqualTo("AUTH403_1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM coach_messages m JOIN coach_conversations c USING(conversation_id) WHERE c.user_id=?",
                Integer.class,
                userId))
        .isZero();
  }

  /** Exercises database-representable inactive states directly through the service boundary. */
  @Test
  void serviceRejectsAllInactiveStatusesEvenWithoutTheAuthFilter() throws Exception {
    long userId = activeUser();
    // The Docker CI fixture intentionally skips V3 and cannot represent ANONYMIZED.
    // That state is covered through the service boundary in CoachServiceTests.
    for (String state : List.of("PENDING_ONBOARDING", "SUSPENDED", "WITHDRAWN")) {
      jdbc.update(
          "UPDATE users SET account_status=?, withdrawn_at=CASE WHEN ?='WITHDRAWN' THEN now() ELSE NULL END, restorable_until=CASE WHEN ?='WITHDRAWN' THEN now()+interval '720 hours' ELSE NULL END, status_before_withdrawal=CASE WHEN ?='WITHDRAWN' THEN 'ACTIVE' ELSE NULL END, provider=CASE WHEN ?='ANONYMIZED' THEN NULL ELSE 'KAKAO' END, provider_user_id=CASE WHEN ?='ANONYMIZED' THEN NULL ELSE ? END, anonymized_at=CASE WHEN ?='ANONYMIZED' THEN now() ELSE NULL END WHERE user_id=?",
          state,
          state,
          state,
          state,
          state,
          state,
          socialId,
          state,
          userId);
      assertThatThrownBy(
              () ->
                  service.chat(
                      userId,
                      UUID.randomUUID().toString(),
                      new com.hobbytree.hobbytree.domain.coach.dto.CoachChatRequest(
                          null, "질문", null)))
          .isInstanceOf(com.hobbytree.hobbytree.domain.auth.exception.AuthException.class);
    }
    verifyNoInteractions(ai);
  }

  @Test
  void mapsPublishedActivityCodesAndPreservesNonNumericSourcesInHistory() throws Exception {
    long userId = activeUser();
    long hobbyId = hobby(userId);
    long stage =
        jdbc.queryForObject(
            "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,0,'격리 테스트') ON CONFLICT(hobby_id,stage_number) DO UPDATE SET stage_name=EXCLUDED.stage_name RETURNING hobby_stage_id",
            Long.class,
            hobbyId);
    String activityCode = "coach-test-" + UUID.randomUUID();
    long activityId =
        jdbc.queryForObject(
            "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status) VALUES (?,?,?,'PUBLISHED') RETURNING activity_id",
            Long.class,
            stage,
            activityCode,
            "DB 활동 제목");
    try {
      when(ai.chat(any()))
          .thenAnswer(
              call -> {
                CoachAiRequest request = call.getArgument(0);
                return new CoachAiResponse(
                    request.conversationId(),
                    "안내",
                    "coach-v3.0.0",
                    "browse",
                    List.of(
                        new CoachAiResponse.Reference("ACTIVITY", activityCode, activityCode, "근거"),
                        new CoachAiResponse.Reference(
                            "KNOWLEDGE", "evidence-x", activityCode, "지식"),
                        new CoachAiResponse.Reference("RECIPE", "recipe-x", null, "레시피")),
                    List.of(new CoachAiResponse.Suggestion(activityCode, "AI 제목", "자료 요약")),
                    false);
              });
      var response =
          assertOkResult(chat(token(userId), UUID.randomUUID().toString(), body(null, "질문", null)));
      assertThat(response.path("references").get(0).path("sourceId").asLong())
          .isEqualTo(activityId);
      assertThat(response.path("references").get(1).path("sourceId").isNull()).isTrue();
      assertThat(response.path("references").get(2).path("sourceCode").asText())
          .isEqualTo("recipe-x");
      assertThat(response.path("suggestedActivities").get(0).path("activityId").asLong())
          .isEqualTo(activityId);
      assertThat(response.path("suggestedActivities").get(0).path("title").asText())
          .isEqualTo("DB 활동 제목");
      var history =
          result(
                  conversationMessages(
                      token(userId), response.path("conversationId").asLong(), null, null))
              .path("items")
              .get(1);
      assertThat(history.path("references")).isEqualTo(response.path("references"));
      assertThat(history.path("suggestedActivities"))
          .isEqualTo(response.path("suggestedActivities"));
      jdbc.update("UPDATE activities SET review_status='DRAFT' WHERE activity_id=?", activityId);
      var hidden = chat(token(userId), UUID.randomUUID().toString(), body(null, "비공개 활동", null));
      assertThat(hidden.statusCode()).isEqualTo(502);
      jdbc.update("DELETE FROM activities WHERE activity_id=?", activityId);
      var missing = chat(token(userId), UUID.randomUUID().toString(), body(null, "없는 활동", null));
      assertThat(missing.statusCode()).isEqualTo(502);
    } finally {
      jdbc.update("DELETE FROM activities WHERE activity_id=?", activityId);
    }
  }

  @Test
  void rejectsUnsupportedHobbyBeforeCallingAi() throws Exception {
    long userId = activeUser();
    long hobbyId =
        jdbc.queryForObject(
            "INSERT INTO hobbies(hobby_code,hobby_name,category,indoor_outdoor) VALUES (?,?,'READING','INDOOR') RETURNING hobby_id",
            Long.class,
            "profile-test-" + userId + "-reading",
            "독서");
    long tree = plantTree(userId, hobbyId);
    var response = chat(token(userId), UUID.randomUUID().toString(), body(null, "질문", tree));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(code(response)).isEqualTo("COACH400_2");
    verifyNoInteractions(ai);
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

  /** Uses the canonical AI hobby code and tracks only a fixture created by this test. */
  private long hobby(long userId) {
    var inserted =
        jdbc.queryForList(
            "INSERT INTO hobbies(hobby_code,hobby_name,category,indoor_outdoor) VALUES ('cooking','격리 테스트 요리','COOKING','INDOOR') ON CONFLICT (hobby_code) DO NOTHING RETURNING hobby_id",
            Long.class);
    if (!inserted.isEmpty()) createdCookingHobby = inserted.get(0);
    return jdbc.queryForObject(
        "SELECT hobby_id FROM hobbies WHERE hobby_code='cooking'", Long.class);
  }

  /** Removes this test's cooking fixture without deleting a pre-existing shared catalog. */
  @AfterEach
  void cleanupCookingFixture() {
    if (createdCookingHobby == null) return;
    jdbc.update("DELETE FROM user_hobbies WHERE hobby_id=?", createdCookingHobby);
    jdbc.update(
        "DELETE FROM activities WHERE hobby_stage_id IN (SELECT hobby_stage_id FROM hobby_stages WHERE hobby_id=?)",
        createdCookingHobby);
    jdbc.update("DELETE FROM hobbies WHERE hobby_id=?", createdCookingHobby);
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

  private long seedConversationAt(long userId, Instant lastMessageAt) {
    long id =
        jdbc.queryForObject(
            "INSERT INTO coach_conversations(user_id,last_message_at) VALUES (?,?) RETURNING"
                + " conversation_id",
            Long.class,
            userId,
            Timestamp.from(lastMessageAt));
    jdbc.update(
        "INSERT INTO coach_messages(conversation_id,role,content) VALUES (?,'ASSISTANT','fixture')",
        id);
    return id;
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
