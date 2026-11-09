package com.hobbytree.hobbytree.domain.coach.dto;

import java.time.Instant;
import java.util.List;

public record CoachChatResponse(
    Long conversationId,
    Long messageId,
    String answer,
    List<CoachReferenceResponse> references,
    List<CoachSuggestedActivityResponse> suggestedActivities,
    CoachUsedContextResponse usedContext,
    Instant createdAt) {}
