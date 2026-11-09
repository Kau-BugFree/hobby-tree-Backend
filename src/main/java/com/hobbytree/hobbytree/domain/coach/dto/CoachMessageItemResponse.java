package com.hobbytree.hobbytree.domain.coach.dto;

import java.time.Instant;
import java.util.List;

public record CoachMessageItemResponse(
    Long messageId,
    String role,
    String content,
    List<CoachReferenceResponse> references,
    List<CoachSuggestedActivityResponse> suggestedActivities,
    Instant createdAt) {}
