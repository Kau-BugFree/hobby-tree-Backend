package com.hobbytree.hobbytree.domain.tree.dto;

import java.util.List;

public record UserHobbyRoadmapResponse(
    Long userHobbyId,
    short currentStageNumber,
    long distinctCompletedActivityCount,
    GrowthRule growthRule,
    List<Stage> stages) {

  public record GrowthRule(int distinctActivitiesPerStage) {}

  public record Stage(
      Long stageId,
      short stageNumber,
      String name,
      StageState state,
      long unlockAtDistinctCompletedCount,
      List<Long> representativeActivityIds) {}

  public enum StageState {
    COMPLETED,
    CURRENT,
    LOCKED
  }
}
