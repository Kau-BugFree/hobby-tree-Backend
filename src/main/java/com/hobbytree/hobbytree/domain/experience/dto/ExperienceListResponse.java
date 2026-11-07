package com.hobbytree.hobbytree.domain.experience.dto;

import java.util.List;

public record ExperienceListResponse(
    List<ExperienceItemResponse> items, String nextCursor, boolean hasNext) {}
