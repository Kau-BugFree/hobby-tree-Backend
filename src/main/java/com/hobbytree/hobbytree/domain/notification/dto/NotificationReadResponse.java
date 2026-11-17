package com.hobbytree.hobbytree.domain.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record NotificationReadResponse(
    Long notificationId,
    @JsonProperty("isRead") boolean isRead,
    Instant readAt,
    long unreadCount) {}
