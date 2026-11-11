package com.hobbytree.hobbytree.domain.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record LatestRecommendationResponse(
    Long recommendationId,
    int preferencesVersion,
    Instant createdAt,
    List<RecommendationItemResponse> items,
    int requestedCount,
    int returnedCount,
    boolean isStale,
    @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode engineResult) {
  public LatestRecommendationResponse(
      Long id,
      int version,
      Instant createdAt,
      List<RecommendationItemResponse> items,
      int requestedCount,
      int returnedCount,
      boolean isStale) {
    this(id, version, createdAt, items, requestedCount, returnedCount, isStale, null);
  }
}
