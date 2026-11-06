package com.hobbytree.hobbytree.domain.tree.dto;

import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import java.time.Instant;
import java.util.List;

public record UserHobbyListResponse(
    List<Item> items,
    String nextCursor,
    boolean hasNext,
    long occupiedTreeCount,
    int maxTreeCount) {

  public record Item(
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
      Instant plantedAt) {}
}
