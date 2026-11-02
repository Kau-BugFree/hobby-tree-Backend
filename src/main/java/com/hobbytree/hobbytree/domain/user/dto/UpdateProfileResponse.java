package com.hobbytree.hobbytree.domain.user.dto;

import java.time.Instant;

public record UpdateProfileResponse(
    Long userId, String nickname, String profileImageUrl, String ageRange, Instant updatedAt) {}
