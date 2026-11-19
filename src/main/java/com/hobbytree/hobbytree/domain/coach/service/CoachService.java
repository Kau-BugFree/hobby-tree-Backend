package com.hobbytree.hobbytree.domain.coach.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.coach.client.CoachAiClient;
import com.hobbytree.hobbytree.domain.coach.code.CoachErrorCode;
import com.hobbytree.hobbytree.domain.coach.dto.CoachChatRequest;
import com.hobbytree.hobbytree.domain.coach.dto.CoachChatResponse;
import com.hobbytree.hobbytree.domain.coach.dto.CoachConversationItemResponse;
import com.hobbytree.hobbytree.domain.coach.dto.CoachConversationListResponse;
import com.hobbytree.hobbytree.domain.coach.dto.CoachConversationMessagesResponse;
import com.hobbytree.hobbytree.domain.coach.dto.CoachMessageItemResponse;
import com.hobbytree.hobbytree.domain.coach.dto.CoachReferenceResponse;
import com.hobbytree.hobbytree.domain.coach.dto.CoachSuggestedActivityResponse;
import com.hobbytree.hobbytree.domain.coach.entity.CoachConversation;
import com.hobbytree.hobbytree.domain.coach.entity.CoachMessage;
import com.hobbytree.hobbytree.domain.coach.repository.CoachConversationRepository;
import com.hobbytree.hobbytree.domain.coach.repository.CoachMessageRepository;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyClaim;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyService;
import com.hobbytree.hobbytree.domain.tree.entity.UserHobby;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// Network orchestration has no transaction; preparation and final persistence commit separately.
@Service
@RequiredArgsConstructor
public class CoachService {
  private static final String ENDPOINT = "POST /api/v1/coach/chat";
  private static final int MIN_MESSAGE_LENGTH = 1;
  private static final int MAX_MESSAGE_LENGTH = 2000;
  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 50;
  private static final int PREVIEW_LENGTH = 80;

  private final UserRepository users;
  private final CoachConversationRepository conversations;
  private final CoachMessageRepository messages;
  private final IdempotencyKeyService idempotencyKeys;
  private final ObjectMapper objectMapper;
  private final CoachTurnTransactions turns;
  private final CoachAiClient ai;

  /**
   * Orchestrates claim, durable reservation, external call and final commit without an outer
   * transaction.
   */
  @Transactional(propagation = Propagation.NEVER)
  public CoachChatResponse chat(Long userId, String idempotencyKey, CoachChatRequest request) {
    requireActiveUser(userId);

    String message = normalizeMessage(request.message());

    String requestHash = RedisAuthStore.hash(canonicalRequest(request, message));
    IdempotencyKeyClaim claim =
        idempotencyKeys.claim(userId, ENDPOINT, idempotencyKey, requestHash);
    switch (claim.outcome()) {
      case CONFLICT -> throw new ProjectException(GeneralErrorCode.IDEMPOTENCY_KEY_CONFLICT);
      case IN_PROGRESS -> throw new ProjectException(GeneralErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS);
      case REPLAY -> {
        return objectMapper.readValue(claim.responseBody(), CoachChatResponse.class);
      }
      case CLAIMED -> {
        // fall through to build a fresh reply below
      }
    }

    try {
      var reservation = turns.prepare(userId, claim, request);
      var answer = ai.chat(reservation.request(message));
      return turns.finish(userId, claim, reservation, message, answer);
    } catch (RuntimeException e) {
      idempotencyKeys.releasePreservingContext(claim);
      throw e;
    }
  }

  @Transactional(readOnly = true)
  public CoachConversationListResponse listConversations(Long userId, String cursor, Integer size) {
    requireActiveUser(userId);
    int pageSize = parseSize(size);
    ConversationCursor position = decodeConversationCursor(cursor);

    List<CoachConversation> rows =
        conversations.findPage(
            userId,
            position != null,
            position == null ? Instant.EPOCH : position.lastMessageAt(),
            position == null ? 0L : position.id(),
            PageRequest.of(0, pageSize + 1));

    boolean hasNext = rows.size() > pageSize;
    List<CoachConversation> page = hasNext ? rows.subList(0, pageSize) : rows;
    String nextCursor = hasNext ? encodeConversationCursor(page.get(page.size() - 1)) : null;

    return new CoachConversationListResponse(
        page.stream().map(this::toConversationItem).toList(), nextCursor, hasNext);
  }

  private CoachConversationItemResponse toConversationItem(CoachConversation c) {
    UserHobby userHobby = c.getUserHobby();
    String preview =
        messages
            .findTopByConversation_ConversationIdOrderByMessageIdDesc(c.getConversationId())
            .map(CoachMessage::getContent)
            .map(this::truncate)
            .orElse(null);
    return new CoachConversationItemResponse(
        c.getConversationId(),
        userHobby == null ? null : userHobby.getUserHobbyId(),
        userHobby == null ? null : userHobby.getHobby().getHobbyId(),
        preview,
        c.getLastMessageAt(),
        c.getCreatedAt());
  }

  private String truncate(String content) {
    return content.length() <= PREVIEW_LENGTH
        ? content
        : content.substring(0, PREVIEW_LENGTH) + "...";
  }

  @Transactional(readOnly = true)
  public CoachConversationMessagesResponse listMessages(
      Long userId, Long conversationId, String cursor, Integer size) {
    requireActiveUser(userId);
    CoachConversation conversation =
        conversations
            .findOwnedByIdWithHobby(conversationId, userId)
            .orElseThrow(() -> new ProjectException(CoachErrorCode.CONVERSATION_NOT_FOUND));

    int pageSize = parseSize(size);
    Long afterId = decodeMessageCursor(cursor);

    List<CoachMessage> rows =
        messages.findPage(
            conversationId,
            afterId != null,
            afterId == null ? 0L : afterId,
            PageRequest.of(0, pageSize + 1));

    boolean hasNext = rows.size() > pageSize;
    List<CoachMessage> page = hasNext ? rows.subList(0, pageSize) : rows;
    String nextCursor = hasNext ? encodeMessageCursor(page.get(page.size() - 1)) : null;

    UserHobby userHobby = conversation.getUserHobby();
    return new CoachConversationMessagesResponse(
        conversation.getConversationId(),
        userHobby == null ? null : userHobby.getUserHobbyId(),
        userHobby == null ? null : userHobby.getHobby().getHobbyId(),
        page.stream().map(this::toMessageItem).toList(),
        nextCursor,
        hasNext);
  }

  private CoachMessageItemResponse toMessageItem(CoachMessage m) {
    return new CoachMessageItemResponse(
        m.getMessageId(),
        m.getRole().name(),
        m.getContent(),
        parseReferences(m.getReferenceItems()),
        parseSuggestedActivities(m.getSuggestedActivities()),
        m.getCreatedAt());
  }

  /** Restores persisted evidence while preserving null IDs for sources outside the activity DB. */
  private List<CoachReferenceResponse> parseReferences(String json) {
    List<CoachReferenceResponse> result = new ArrayList<>();
    objectMapper
        .readTree(json)
        .forEach(
            n ->
                result.add(
                    new CoachReferenceResponse(
                        n.path("sourceType").asText(null),
                        n.path("sourceId").isNumber() ? n.path("sourceId").asLong() : null,
                        n.path("title").asText(null),
                        n.path("sourceCode").asText(null))));
    return result;
  }

  /** Restores the validated activity suggestions from the stored assistant message. */
  private List<CoachSuggestedActivityResponse> parseSuggestedActivities(String json) {
    List<CoachSuggestedActivityResponse> result = new ArrayList<>();
    objectMapper
        .readTree(json)
        .forEach(
            n ->
                result.add(
                    new CoachSuggestedActivityResponse(
                        n.path("activityId").isNumber() ? n.path("activityId").asLong() : null,
                        n.path("title").asText(null),
                        n.path("reason").asText(null))));
    return result;
  }

  /** Checks membership before serving either a fresh request or a cached response. */
  private void requireActiveUser(Long userId) {
    User user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    CoachAccess.requireActive(user);
  }

  private String normalizeMessage(String message) {
    if (message == null) throw new ProjectException(CoachErrorCode.INVALID_MESSAGE);
    String trimmed = message.strip();
    if (trimmed.length() < MIN_MESSAGE_LENGTH || trimmed.length() > MAX_MESSAGE_LENGTH)
      throw new ProjectException(CoachErrorCode.INVALID_MESSAGE);
    return trimmed;
  }

  private int parseSize(Integer size) {
    if (size == null) return DEFAULT_SIZE;
    if (size < 1 || size > MAX_SIZE)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    return size;
  }

  private String canonicalRequest(CoachChatRequest request, String message) {
    return "conversationId="
        + request.conversationId()
        + ";userHobbyId="
        + request.userHobbyId()
        + ";message="
        + message;
  }

  private record ConversationCursor(Instant lastMessageAt, Long id) {}

  // Opaque cursor: base64("{lastMessageAt}_{conversationId}") - same encoding NotificationService
  // uses for its createdAt/id cursor.
  private ConversationCursor decodeConversationCursor(String cursor) {
    if (cursor == null) return null;
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = decoded.split("_", 2);
      return new ConversationCursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
    } catch (RuntimeException e) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
  }

  private String encodeConversationCursor(CoachConversation last) {
    String raw = last.getLastMessageAt() + "_" + last.getConversationId();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  // Within one conversation, insertion order == messageId order, so the cursor is just the last
  // seen messageId - no timestamp tiebreak needed, unlike the conversation list above.
  private Long decodeMessageCursor(String cursor) {
    if (cursor == null) return null;
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      return Long.parseLong(decoded);
    } catch (RuntimeException e) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
  }

  private String encodeMessageCursor(CoachMessage last) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(String.valueOf(last.getMessageId()).getBytes(StandardCharsets.UTF_8));
  }
}
