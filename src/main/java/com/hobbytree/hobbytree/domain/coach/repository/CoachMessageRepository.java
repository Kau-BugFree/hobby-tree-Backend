package com.hobbytree.hobbytree.domain.coach.repository;

import com.hobbytree.hobbytree.domain.coach.entity.CoachMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoachMessageRepository extends JpaRepository<CoachMessage, Long> {
  Optional<CoachMessage> findTopByConversation_ConversationIdOrderByMessageIdDesc(
      Long conversationId);

  @Query(
      """
      SELECT m FROM CoachMessage m
      WHERE m.conversation.conversationId = :conversationId
        AND (:hasCursor = FALSE OR m.messageId > :afterId)
      ORDER BY m.messageId ASC
      """)
  List<CoachMessage> findPage(
      @Param("conversationId") Long conversationId,
      @Param("hasCursor") boolean hasCursor,
      @Param("afterId") long afterId,
      Pageable pageable);
}
