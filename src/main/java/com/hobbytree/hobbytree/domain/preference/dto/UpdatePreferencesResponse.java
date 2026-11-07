package com.hobbytree.hobbytree.domain.preference.dto;

import java.time.Instant;
import java.util.List;

public record UpdatePreferencesResponse(
    String optionsVersion,
    String budgetLevel,
    int availableTimeMinutes,
    String indoorOutdoor,
    String difficultyPreference,
    List<Integer> preferredCategoryIds,
    Long userId,
    int preferencesVersion,
    Instant updatedAt) {}
