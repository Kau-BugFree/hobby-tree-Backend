package com.hobbytree.hobbytree.domain.user.dto;

import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.AuthProvider;
import java.time.Instant;

// location/locationConsent from API-05 are intentionally omitted: they depend on the "regions"
// table API.md itself marks as a new, unconfirmed proposal (API-41/42/43), not something to
// design here.
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
    Instant createdAt) {}
