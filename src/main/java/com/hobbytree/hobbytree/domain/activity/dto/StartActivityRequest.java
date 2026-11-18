package com.hobbytree.hobbytree.domain.activity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record StartActivityRequest(@NotNull @Positive Long activityId) {}
