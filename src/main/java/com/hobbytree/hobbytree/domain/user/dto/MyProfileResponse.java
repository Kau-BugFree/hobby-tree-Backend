package com.hobbytree.hobbytree.domain.user.dto;

import com.hobbytree.hobbytree.domain.location.dto.LocationConsentResponse;
import com.hobbytree.hobbytree.domain.location.dto.LocationResponse;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.AuthProvider;
import java.time.Instant;

public record MyProfileResponse(
    Long userId,
    String nickname,
    String profileImageUrl,
    AuthProvider provider,
    String ageRange,
    AccountStatus memberStatus,
    boolean onboardingCompleted,
    long treeCount,
    int maxTreeCount,
    NotificationSettingsResponse notificationSettings,
    Instant createdAt,
    LocationResponse location,
    LocationConsentResponse locationConsent) {}
