package com.hobbytree.hobbytree.domain.activity.dto;

import java.time.Instant;
import java.util.List;

public record ActivityRecordDetailResponse(
    Long recordId,
    Long userHobbyId,
    Long activityId,
    String title,
    String status,
    String treeStatus,
    long contentVersion,
    List<ActivityDetailResponse.Step> steps,
    List<Long> completedStepIds,
    int progressPercent,
    int elapsedSeconds,
    long version,
    boolean canUpdate,
    Instant startedAt,
    Instant finishedAt,
    Feedback feedback) {
  public record Feedback(Integer satisfactionScore, String perceivedDifficulty, String memo) {}
}
