package com.hobbytree.hobbytree.domain.hobby.dto;

import java.time.Instant;
import java.util.List;

public record HobbyDetailResponse(
    Long hobbyId,
    String name,
    String description,
    HobbyCategoryResponse category,
    String thumbnailUrl,
    Integer minDurationMinutes,
    Integer minCost,
    String difficulty,
    String indoorOutdoor,
    boolean isPlanted,
    Long userHobbyId,
    String introduction,
    int totalStages,
    HobbyGrowthRuleResponse growthRule,
    List<StarterMaterialResponse> starterMaterials,
    String treeStatus,
    Instant contentUpdatedAt) {}
