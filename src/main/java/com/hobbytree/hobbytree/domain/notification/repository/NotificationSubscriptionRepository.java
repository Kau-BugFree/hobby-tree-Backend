package com.hobbytree.hobbytree.domain.notification.repository;

import com.hobbytree.hobbytree.domain.notification.entity.NotificationSubscription;
import com.hobbytree.hobbytree.domain.notification.entity.SubscriptionType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSubscriptionRepository
    extends JpaRepository<NotificationSubscription, Long> {
  // Matches the partial unique index uq_sub_chat (CHAT_LAUNCH, unsubscribed_at IS NULL).
  Optional<NotificationSubscription> findByUser_UserIdAndSubscriptionTypeAndUnsubscribedAtIsNull(
      Long userId, SubscriptionType subscriptionType);

  // Matches the partial unique index uq_sub_class (CLASS_OPEN, unsubscribed_at IS NULL).
  Optional<NotificationSubscription>
      findByUser_UserIdAndExperience_ExperienceIdAndSubscriptionTypeAndUnsubscribedAtIsNull(
          Long userId, Long experienceId, SubscriptionType subscriptionType);
}
