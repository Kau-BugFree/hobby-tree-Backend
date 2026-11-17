package com.hobbytree.hobbytree.domain.experience.dto;

import java.time.Instant;

public record ExperienceItemResponse(
    Long experienceId,
    String type,
    String title,
    Long hobbyId,
    String hobbyName,
    String thumbnailUrl,
    RegionSummaryResponse region,
    ProviderSummaryResponse provider,
    Integer price,
    String currency,
    String status,
    NextSessionResponse nextSession,
    Instant providerUpdatedAt) {}
