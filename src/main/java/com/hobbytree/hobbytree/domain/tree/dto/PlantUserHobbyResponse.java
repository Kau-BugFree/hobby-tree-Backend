package com.hobbytree.hobbytree.domain.tree.dto;

import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import java.time.Instant;

public record PlantUserHobbyResponse(
    Long userHobbyId,
    Long hobbyId,
    String hobbyName,
    TreeStatus treeStatus,
    short currentStageNumber,
    long distinctCompletedActivityCount,
    Instant plantedAt) {}
