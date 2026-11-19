package com.hobbytree.hobbytree.domain.activity.dto;

import java.time.Instant;
import java.util.List;

public record SaveActivityProgressResponse(
    long recordId,
    String status,
    List<Long> completedStepIds,
    int progressPercent,
    int elapsedSeconds,
    long version,
    Instant updatedAt) {}
