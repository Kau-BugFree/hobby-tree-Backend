package com.hobbytree.hobbytree.domain.notification.repository;

import com.hobbytree.hobbytree.domain.notification.entity.UserNotification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {}
