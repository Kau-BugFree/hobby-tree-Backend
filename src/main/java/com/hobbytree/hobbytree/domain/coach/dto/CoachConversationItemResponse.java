package com.hobbytree.hobbytree.domain.coach.dto;

import java.time.Instant;

public record CoachConversationItemResponse(
    Long conversationId,
    Long userHobbyId,
    Long hobbyId,
    String lastMessagePreview,
    Instant lastMessageAt,
    Instant createdAt) {}
