package com.hobbytree.hobbytree.domain.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record NotificationItemResponse(
    Long notificationId,
    String type,
    String title,
    String body,
    NotificationTargetResponse target,
    @JsonProperty("isRead") boolean isRead,
    Instant readAt,
    Instant createdAt) {}
