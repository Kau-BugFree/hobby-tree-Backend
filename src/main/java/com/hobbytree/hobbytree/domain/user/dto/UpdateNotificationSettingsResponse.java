package com.hobbytree.hobbytree.domain.user.dto;

import java.time.Instant;

public record UpdateNotificationSettingsResponse(
    Long userId, NotificationSettingsResponse notificationSettings, Instant updatedAt) {}
