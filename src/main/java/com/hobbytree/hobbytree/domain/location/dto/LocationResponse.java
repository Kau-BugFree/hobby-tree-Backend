package com.hobbytree.hobbytree.domain.location.dto;

import java.time.Instant;

public record LocationResponse(
    Long regionId,
    String name,
    String fullName,
    String level,
    Long parentRegionId,
    String source,
    Instant updatedAt) {}
