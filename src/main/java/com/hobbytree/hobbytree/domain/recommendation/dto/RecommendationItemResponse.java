package com.hobbytree.hobbytree.domain.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

public record RecommendationItemResponse(
    Long hobbyId,
    String name,
    String thumbnailUrl,
    String reason,
    List<String> matchedCriteria,
    @JsonInclude(JsonInclude.Include.NON_NULL) String hobbyCode,
    @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal score) {
  public RecommendationItemResponse(
      Long id, String name, String thumbnail, String reason, List<String> criteria) {
    this(id, name, thumbnail, reason, criteria, null, null);
  }
}
