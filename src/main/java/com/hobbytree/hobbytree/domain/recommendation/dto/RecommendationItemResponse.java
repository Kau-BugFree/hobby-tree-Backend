package com.hobbytree.hobbytree.domain.recommendation.dto;

import java.util.List;

public record RecommendationItemResponse(
    Long hobbyId, String name, String thumbnailUrl, String reason, List<String> matchedCriteria) {}
