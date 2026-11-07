package com.hobbytree.hobbytree.domain.hobby.dto;

import java.util.List;

public record HobbyStageListResponse(Long hobbyId, String hobbyName, List<Stage> stages) {
  public record Stage(
      Long stageId,
      short stageNumber,
      String name,
      String description,
      long activityCount,
      long unlockAtDistinctCompletedCount) {}
}
