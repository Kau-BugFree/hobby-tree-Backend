package com.hobbytree.hobbytree.domain.experience.dto;

public record AttendanceGrowthResponse(
    boolean growthApplied,
    long distinctCompletedActivityCount,
    int previousStageNumber,
    int currentStageNumber,
    boolean stageChanged) {}
