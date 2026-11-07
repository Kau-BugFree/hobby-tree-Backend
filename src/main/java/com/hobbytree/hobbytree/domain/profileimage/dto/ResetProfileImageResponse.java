package com.hobbytree.hobbytree.domain.profileimage.dto;

import java.time.Instant;

public record ResetProfileImageResponse(Long userId, String profileImageUrl, Instant updatedAt) {}
