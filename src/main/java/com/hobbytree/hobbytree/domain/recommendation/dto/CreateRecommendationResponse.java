package com.hobbytree.hobbytree.domain.recommendation.dto;

import java.time.Instant;
import java.util.List;

public record CreateRecommendationResponse(
    Long recommendationId,
    int preferencesVersion,
    Instant createdAt,
    List<RecommendationItemResponse> items,
    int requestedCount,
    int returnedCount) {}
