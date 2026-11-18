package com.hobbytree.hobbytree.domain.coach.entity;

import com.hobbytree.hobbytree.domain.tree.entity.UserHobby;
import com.hobbytree.hobbytree.domain.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SourceType;

@Entity
@Table(name = "coach_conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CoachConversation {

  // userHobby is optional context fixed when the conversation starts - a later message in the
  // same conversation can't retarget it (CoachService.chat ignores userHobbyId once a
  // conversationId is given), so it's never reassigned after this.
  public static CoachConversation start(User user, UserHobby userHobby, Instant now) {
    CoachConversation conversation = new CoachConversation();
    conversation.user = user;
    conversation.userHobby = userHobby;
    conversation.lastMessageAt = now;
    return conversation;
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "conversation_id", nullable = false)
  private Long conversationId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User user;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "user_hobby_id", nullable = true)
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private UserHobby userHobby;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_message_at", nullable = false)
  private Instant lastMessageAt;

  // Bumped on every new turn in this conversation so the "대화 이력" list can sort by recency.
  public void touch(Instant now) {
    this.lastMessageAt = now;
  }
}
