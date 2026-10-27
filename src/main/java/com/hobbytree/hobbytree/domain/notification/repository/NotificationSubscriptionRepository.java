package com.hobbytree.hobbytree.domain.notification.repository;

import com.hobbytree.hobbytree.domain.notification.entity.NotificationSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSubscriptionRepository
    extends JpaRepository<NotificationSubscription, Long> {}
