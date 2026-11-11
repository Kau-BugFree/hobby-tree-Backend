package com.hobbytree.hobbytree.domain.coach.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.coach.client.CoachAiRequest;
import com.hobbytree.hobbytree.domain.coach.client.CoachAiResponse;
import com.hobbytree.hobbytree.domain.coach.code.CoachErrorCode;
import com.hobbytree.hobbytree.domain.coach.dto.*;
import com.hobbytree.hobbytree.domain.coach.entity.CoachConversation;
import com.hobbytree.hobbytree.domain.coach.entity.CoachMessage;
import com.hobbytree.hobbytree.domain.coach.repository.*;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyClaim;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyService;
import com.hobbytree.hobbytree.domain.tree.code.TreeErrorCode;
import com.hobbytree.hobbytree.domain.tree.entity.UserHobby;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class CoachTurnTransactions {
  private final UserRepository users;
  private final UserHobbyRepository userHobbies;
  private final CoachConversationRepository conversations;
  private final CoachMessageRepository messages;
  private final CoachActivityRepository activities;
  private final IdempotencyKeyService keys;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final EntityManager entityManager;

  // Only stable identifiers are stored in the pending response_body. The user's prompt stays
  // in the request until the final transaction; the existing request hash enforces retry equality.
  public record Reservation(Long conversationId, String requestId, String hobbyCode) {
    /** Reuses the durable identifiers with the already hash-validated prompt. */
    public CoachAiRequest request(String message) {
      return new CoachAiRequest(conversationId, message, requestId, hobbyCode);
    }
  }

  /** Commits a recoverable conversation reservation without holding locks during the AI call. */
  @Transactional
  public Reservation prepare(Long userId, IdempotencyKeyClaim claim, CoachChatRequest request) {
    var user = activeUserForUpdate(userId);
    String context = keys.lockPendingContext(claim);
    Reservation reservation = context == null ? null : mapper.readValue(context, Reservation.class);
    Long conversationId =
        reservation == null ? request.conversationId() : reservation.conversationId();
    CoachConversation conversation;
    if (conversationId == null) {
      UserHobby userHobby =
          request.userHobbyId() == null
              ? null
              : userHobbies
                  .findOwnedByIdWithHobby(request.userHobbyId(), userId)
                  .orElseThrow(() -> new ProjectException(TreeErrorCode.NOT_FOUND));
      requireCooking(userHobby);
      conversation =
          conversations.saveAndFlush(
              CoachConversation.start(
                  user, userHobby, clock.instant().truncatedTo(ChronoUnit.MICROS)));
    } else {
      conversation = owned(conversationId, userId);
      requireCooking(conversation.getUserHobby());
    }
    if (conversations.hasPendingTurn(
        userId, conversation.getConversationId(), claim.idempotencyKeyId()))
      throw new ProjectException(CoachErrorCode.TURN_IN_PROGRESS);
    if (reservation == null) {
      reservation =
          new Reservation(
              conversation.getConversationId(),
              "coach-turn-" + claim.idempotencyKeyId(),
              "cooking");
      keys.savePendingContext(claim, mapper.writeValueAsString(reservation));
    }
    return reservation;
  }

  /**
   * Atomically saves both messages and the replay result after rechecking ownership and catalog.
   */
  @Transactional
  public CoachChatResponse finish(
      Long userId,
      IdempotencyKeyClaim claim,
      Reservation reservation,
      String message,
      CoachAiResponse answer) {
    activeUserForUpdate(userId);
    String context = keys.lockPendingContext(claim);
    if (!reservation.equals(mapper.readValue(context, Reservation.class)))
      throw new ProjectException(CoachErrorCode.AI_BAD_RESPONSE);
    var conversation = owned(reservation.conversationId(), userId);
    requireCooking(conversation.getUserHobby());
    if (!reservation.conversationId().equals(answer.conversationId()))
      throw new ProjectException(CoachErrorCode.AI_BAD_RESPONSE);

    var codes = new HashSet<String>();
    answer
        .references()
        .forEach(
            r -> {
              if (r.activityCode() != null) codes.add(r.activityCode());
            });
    answer.suggestedActivities().forEach(s -> codes.add(s.activityCode()));
    var catalog =
        activities.findPublishedCodes(codes, reservation.hobbyCode()).stream()
            .collect(Collectors.toMap(a -> a.getActivityCode(), Function.identity()));
    if (!catalog.keySet().containsAll(codes))
      throw new ProjectException(CoachErrorCode.AI_BAD_RESPONSE);
    var references =
        answer.references().stream()
            .map(
                r ->
                    new CoachReferenceResponse(
                        r.sourceType(),
                        "ACTIVITY".equals(r.sourceType())
                            ? catalog.get(r.activityCode()).getActivityId()
                            : null,
                        r.title(),
                        r.sourceCode()))
            .toList();
    var suggestions =
        answer.suggestedActivities().stream()
            .map(
                s ->
                    new CoachSuggestedActivityResponse(
                        catalog.get(s.activityCode()).getActivityId(),
                        catalog.get(s.activityCode()).getActivityName(),
                        s.reason()))
            .toList();

    messages.save(CoachMessage.user(conversation, message));
    var reply =
        messages.saveAndFlush(
            CoachMessage.assistant(
                conversation,
                answer.answer(),
                answer.answerVersion(),
                mapper.writeValueAsString(references),
                mapper.writeValueAsString(suggestions)));
    conversation.touch(clock.instant().truncatedTo(ChronoUnit.MICROS));
    var tree = conversation.getUserHobby();
    var response =
        new CoachChatResponse(
            conversation.getConversationId(),
            reply.getMessageId(),
            answer.answer(),
            references,
            suggestions,
            tree == null
                ? null
                : new CoachUsedContextResponse(tree.getUserHobbyId(), tree.getHobby().getHobbyId()),
            reply.getCreatedAt());
    keys.complete(
        claim.idempotencyKeyId(), claim.ownershipToken(), 200, mapper.writeValueAsString(response));
    return response;
  }

  /** Locks only the caller's conversation and reloads its current optional tree association. */
  private CoachConversation owned(Long id, Long userId) {
    var conversation =
        conversations
            .lockOwned(id, userId)
            .orElseThrow(() -> new ProjectException(CoachErrorCode.CONVERSATION_NOT_FOUND));
    entityManager.refresh(conversation);
    return conversation;
  }

  /** Serializes short state checks with account changes and bypasses a possible OSIV cache. */
  private User activeUserForUpdate(Long userId) {
    var user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    // Also correct when OSIV is enabled: the first request check may have cached ACTIVE.
    entityManager.refresh(user);
    return CoachAccess.requireActive(user);
  }

  /**
   * Accepts general cooking chat or a supported cooking tree; other contexts cannot be retargeted.
   */
  private void requireCooking(UserHobby tree) {
    if (tree != null
        && (!"cooking".equals(tree.getHobby().getHobbyCode())
            || !Boolean.TRUE.equals(tree.getHobby().getIsSupported())))
      throw new ProjectException(CoachErrorCode.UNSUPPORTED_HOBBY);
  }
}
