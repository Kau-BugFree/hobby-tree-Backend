package com.hobbytree.hobbytree.domain.preference.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record UpdatePreferencesResponse(
    String optionsVersion,
    String budgetLevel,
    Integer availableTimeMinutes,
    String indoorOutdoor,
    String difficultyPreference,
    List<Integer> preferredCategoryIds,
    Long userId,
    int preferencesVersion,
    Instant updatedAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode questionnaire) {}
