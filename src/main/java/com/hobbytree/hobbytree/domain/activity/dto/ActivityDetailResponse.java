package com.hobbytree.hobbytree.domain.activity.dto;

import java.time.Instant;
import java.util.List;

public record ActivityDetailResponse(
    Long activityId,
    String title,
    String thumbnailUrl,
    Integer durationMinutes,
    Integer estimatedCost,
    String difficulty,
    String indoorOutdoor,
    boolean isSaved,
    Long hobbyId,
    Long stageId,
    short stageNumber,
    String description,
    Long contentVersion,
    List<Material> materials,
    List<Step> steps,
    List<String> cautions,
    Instant contentUpdatedAt) {
  public record Material(
      Long materialId, String name, String quantity, Boolean isRequired, String acquisitionType) {}

  // Nullable legacy fields retain unknown meaning until catalog curation.
  public record Step(
      Long stepId,
      short stepNumber,
      String title,
      String description,
      Boolean isRequired,
      String imageUrl) {}
}
