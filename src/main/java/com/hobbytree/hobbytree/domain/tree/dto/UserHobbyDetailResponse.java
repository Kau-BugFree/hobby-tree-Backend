package com.hobbytree.hobbytree.domain.tree.dto;

import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import java.time.Instant;

public record UserHobbyDetailResponse(
    Long userHobbyId,
    Long hobbyId,
    String hobbyName,
    String thumbnailUrl,
    TreeStatus treeStatus,
    short currentStageNumber,
    String currentStageName,
    long distinctCompletedActivityCount,
    Integer requiredCountForNextStage,
    long remainingCountForNextStage,
    Instant plantedAt,
    int totalStages,
    boolean isMaxStage,
    long currentStageCompletedCount,
    Integer currentStageRequiredCount,
    CurrentActivityRecord currentActivityRecord,
    Instant lastActivityAt) {

  public record CurrentActivityRecord(
      Long recordId, Long activityId, String title, Integer progressPercent) {}
}
