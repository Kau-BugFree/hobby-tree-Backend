package com.hobbytree.hobbytree.domain.coach.repository;

import com.hobbytree.hobbytree.domain.coach.entity.CoachConversation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoachConversationRepository extends JpaRepository<CoachConversation, Long> {
  // Lock only the conversation, not the nullable side of an outer join (PostgreSQL rejects it).
  @Query(
      value =
          "SELECT * FROM coach_conversations WHERE conversation_id = :id AND user_id = :userId FOR UPDATE",
      nativeQuery = true)
  Optional<CoachConversation> lockOwned(@Param("id") Long id, @Param("userId") Long userId);

  // A failed/unknown upstream outcome must be replayed before another turn advances this
  // session. Expired leases allow the SAME key to retry, not another message to overtake it.
  @Query(
      value =
          "SELECT EXISTS (SELECT 1 FROM idempotency_keys WHERE user_id = :userId AND endpoint = 'POST /api/v1/coach/chat' AND status = 'PENDING' AND idempotency_key_id <> :claimId AND response_body ->> 'conversationId' = CAST(:conversationId AS text))",
      nativeQuery = true)
  boolean hasPendingTurn(
      @Param("userId") Long userId,
      @Param("conversationId") Long conversationId,
      @Param("claimId") Long claimId);

  @Query(
      """
      SELECT c FROM CoachConversation c LEFT JOIN FETCH c.userHobby uh LEFT JOIN FETCH uh.hobby
      WHERE c.conversationId = :conversationId AND c.user.userId = :userId
      """)
  Optional<CoachConversation> findOwnedByIdWithHobby(
      @Param("conversationId") Long conversationId, @Param("userId") Long userId);

  // "대화 이력" 목록: lastMessageAt DESC, id DESC tiebreak - same cursor shape as
  // NotificationService.list's createdAt/id cursor.
  @Query(
      """
      SELECT c FROM CoachConversation c LEFT JOIN FETCH c.userHobby uh LEFT JOIN FETCH uh.hobby
      WHERE c.user.userId = :userId
        AND EXISTS (SELECT m.messageId FROM CoachMessage m WHERE m.conversation = c)
        AND (:hasCursor = FALSE
             OR c.lastMessageAt < :beforeLastMessageAt
             OR (c.lastMessageAt = :beforeLastMessageAt AND c.conversationId < :beforeId))
      ORDER BY c.lastMessageAt DESC, c.conversationId DESC
      """)
  List<CoachConversation> findPage(
      @Param("userId") Long userId,
      @Param("hasCursor") boolean hasCursor,
      @Param("beforeLastMessageAt") Instant beforeLastMessageAt,
      @Param("beforeId") long beforeId,
      Pageable pageable);
}
