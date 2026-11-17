package com.hobbytree.hobbytree.domain.recommendation.dto;

import java.util.List;

public record CreateRecommendationRequest(
    Integer preferencesVersion, Integer count, List<Long> excludeHobbyIds) {}
