package com.hobbytree.hobbytree.domain.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record CreateRecommendationResponse(
    Long recommendationId,
    int preferencesVersion,
    Instant createdAt,
    List<RecommendationItemResponse> items,
    int requestedCount,
    int returnedCount,
    @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode engineResult) {
  public CreateRecommendationResponse(
      Long id,
      int version,
      Instant createdAt,
      List<RecommendationItemResponse> items,
      int requestedCount,
      int returnedCount) {
    this(id, version, createdAt, items, requestedCount, returnedCount, null);
  }
}
