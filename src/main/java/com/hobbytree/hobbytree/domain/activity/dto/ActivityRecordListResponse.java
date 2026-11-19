package com.hobbytree.hobbytree.domain.activity.dto;

import java.time.Instant;
import java.util.List;

public record ActivityRecordListResponse(
    long userHobbyId, List<Item> items, String nextCursor, boolean hasNext) {
  public record Item(
      long recordId,
      long activityId,
      String title,
      String status,
      Integer progressPercent,
      Instant startedAt,
      Instant finishedAt,
      Integer satisfactionScore,
      String perceivedDifficulty,
      boolean growthApplied) {}
}
