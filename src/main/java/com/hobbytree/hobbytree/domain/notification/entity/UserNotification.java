package com.hobbytree.hobbytree.domain.notification.entity;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
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
@Table(name = "user_notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserNotification {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "notification_id", nullable = false)
  private Long notificationId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User user;

  @Column(name = "notification_type", nullable = false, length = 40)
  private String notificationType;

  @Column(name = "title", nullable = false, length = 160)
  private String title;

  @Column(name = "message", nullable = false, columnDefinition = "text")
  private String message;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "related_user_hobby_id", nullable = true)
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private UserHobby relatedUserHobby;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "related_activity_id", nullable = true)
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private Activity relatedActivity;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "read_at", nullable = true)
  private Instant readAt;
}
