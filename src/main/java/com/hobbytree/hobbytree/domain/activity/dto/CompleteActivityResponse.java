package com.hobbytree.hobbytree.domain.activity.dto;

import java.time.Instant;

public record CompleteActivityResponse(
    long recordId,
    long userHobbyId,
    long activityId,
    String status,
    int progressPercent,
    long version,
    Instant finishedAt,
    ActivityRecordDetailResponse.Feedback feedback,
    Growth growth) {
  public record Growth(
      boolean growthApplied,
      long distinctCompletedActivityCount,
      short previousStageNumber,
      short currentStageNumber,
      boolean stageChanged,
      boolean isMaxStage) {}
}
