package com.hobbytree.hobbytree.domain.hobby.dto;

public record HobbyItemResponse(
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
    Long userHobbyId) {}
