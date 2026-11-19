package com.hobbytree.hobbytree.domain.activity.dto;

import java.time.Instant;
import java.util.List;

public record StartActivityResponse(
    Long recordId,
    Long userHobbyId,
    Long activityId,
    String status,
    long contentVersion,
    List<Long> completedStepIds,
    int progressPercent,
    long version,
    Instant startedAt,
    boolean reused) {}
