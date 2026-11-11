package com.hobbytree.hobbytree.domain.coach.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SourceType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "coach_messages")
@Check(constraints = "(role IN ('USER','ASSISTANT'))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CoachMessage {

  public static CoachMessage user(CoachConversation conversation, String content) {
    CoachMessage message = new CoachMessage();
    message.conversation = conversation;
    message.role = CoachMessageRole.USER;
    message.content = content;
    return message;
  }

  public static CoachMessage assistant(
      CoachConversation conversation,
      String content,
      String answerVersion,
      String references,
      String suggestions) {
    CoachMessage message = new CoachMessage();
    message.conversation = conversation;
    message.role = CoachMessageRole.ASSISTANT;
    message.content = content;
    message.answerVersion = answerVersion;
    message.referenceItems = references;
    message.suggestedActivities = suggestions;
    return message;
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "message_id", nullable = false)
  private Long messageId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "conversation_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private CoachConversation conversation;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "role", nullable = false, length = 20)
  private CoachMessageRole role;

  @Column(name = "content", nullable = false, columnDefinition = "text")
  private String content;

  @Column(name = "answer_version", nullable = true, length = 80)
  private String answerVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @ColumnDefault("'[]'::jsonb")
  @Column(name = "reference_items", nullable = false, columnDefinition = "jsonb")
  private String referenceItems = "[]";

  @JdbcTypeCode(SqlTypes.JSON)
  @ColumnDefault("'[]'::jsonb")
  @Column(name = "suggested_activities", nullable = false, columnDefinition = "jsonb")
  private String suggestedActivities = "[]";

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
