package com.hobbytree.hobbytree.domain.onboarding.dto;

import java.time.Instant;

public record CompleteOnboardingResponse(
    Long userId, String memberStatus, boolean onboardingCompleted, Instant completedAt) {}
