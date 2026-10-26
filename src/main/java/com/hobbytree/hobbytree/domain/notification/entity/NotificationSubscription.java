package com.hobbytree.hobbytree.domain.notification.entity;

import com.hobbytree.hobbytree.domain.experience.entity.Experience;
import com.hobbytree.hobbytree.domain.user.entity.User;
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
@Table(name = "notification_subscriptions")
@Check(
    constraints =
        "(subscription_type IN ('CLASS_OPEN','CHAT_LAUNCH')) AND ((subscription_type='CLASS_OPEN' AND experience_id IS NOT NULL) OR (subscription_type='CHAT_LAUNCH' AND experience_id IS NULL))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSubscription {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "subscription_id", nullable = false)
  private Long subscriptionId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User user;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "subscription_type", nullable = false, length = 40)
  private SubscriptionType subscriptionType;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "experience_id", nullable = true)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Experience experience;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "subscribed_at", nullable = false)
  private Instant subscribedAt;

  @Column(name = "unsubscribed_at", nullable = true)
  private Instant unsubscribedAt;
}
