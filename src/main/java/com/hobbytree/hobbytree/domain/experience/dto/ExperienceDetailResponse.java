package com.hobbytree.hobbytree.domain.experience.dto;

import java.time.Instant;
import java.util.List;

public record ExperienceDetailResponse(
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
    Instant providerUpdatedAt,
    String description,
    String address,
    String venueName,
    List<String> materials,
    List<SessionDetailResponse> sessions,
    String bookingUrl,
    String cancellationPolicyUrl,
    ActivityRecognitionResponse activityRecognition,
    boolean isOpenNotificationSubscribed) {}
