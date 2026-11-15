package com.hobbytree.hobbytree.domain.activity.dto;

import java.util.List;

public record StageActivityListResponse(
    Long stageId,
    Long hobbyId,
    short stageNumber,
    List<Item> items,
    String nextCursor,
    boolean hasNext) {
  public record Item(
      Long activityId,
      String title,
      String thumbnailUrl,
      Integer durationMinutes,
      Integer estimatedCost,
      String difficulty,
      String indoorOutdoor,
      boolean isSaved) {}
}
