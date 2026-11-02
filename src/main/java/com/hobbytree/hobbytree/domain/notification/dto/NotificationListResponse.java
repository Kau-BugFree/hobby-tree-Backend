package com.hobbytree.hobbytree.domain.notification.dto;

import java.util.List;

public record NotificationListResponse(
    List<NotificationItemResponse> items, String nextCursor, boolean hasNext, long unreadCount) {}
