package com.hobbytree.hobbytree.domain.activity.dto;

import java.time.Instant;

public record SaveActivityResponse(Long activityId, boolean isSaved, Instant savedAt) {}
