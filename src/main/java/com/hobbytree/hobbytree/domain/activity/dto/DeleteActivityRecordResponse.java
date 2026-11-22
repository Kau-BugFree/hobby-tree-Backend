package com.hobbytree.hobbytree.domain.activity.dto;

import java.time.Instant;

public record DeleteActivityRecordResponse(
    long recordId,
    long userHobbyId,
    long activityId,
    boolean deleted,
    Instant deletedAt,
    Growth growth) {
  public record Growth(
      boolean growthRevoked,
      long distinctCompletedActivityCount,
      int previousStageNumber,
      int currentStageNumber,
      boolean stageChanged,
      boolean isMaxStage) {}
}
