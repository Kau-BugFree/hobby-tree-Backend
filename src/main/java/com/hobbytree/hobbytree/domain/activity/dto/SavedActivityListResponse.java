package com.hobbytree.hobbytree.domain.activity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record SavedActivityListResponse(List<Item> items, String nextCursor, boolean hasNext) {
  @Schema(oneOf = {AvailableItem.class, UnavailableItem.class})
  public sealed interface Item permits AvailableItem, UnavailableItem {}

  public record AvailableItem(
      Long activityId,
      String title,
      String thumbnailUrl,
      Integer durationMinutes,
      Integer estimatedCost,
      String difficulty,
      String indoorOutdoor,
      boolean isSaved,
      Long hobbyId,
      String hobbyName,
      Long stageId,
      short stageNumber,
      Instant savedAt,
      boolean isAvailable)
      implements Item {}

  public record UnavailableItem(Long activityId, Instant savedAt, boolean isAvailable, String title)
      implements Item {}
}
