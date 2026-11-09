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
