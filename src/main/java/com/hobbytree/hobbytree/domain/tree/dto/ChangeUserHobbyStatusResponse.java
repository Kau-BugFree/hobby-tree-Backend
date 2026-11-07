package com.hobbytree.hobbytree.domain.tree.dto;

import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import java.time.Instant;

public record ChangeUserHobbyStatusResponse(
    Long userHobbyId,
    TreeStatus treeStatus,
    short currentStageNumber,
    Instant pausedAt,
    Instant endedAt,
    Instant updatedAt) {}
