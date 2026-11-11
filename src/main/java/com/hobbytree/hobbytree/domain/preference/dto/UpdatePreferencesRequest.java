package com.hobbytree.hobbytree.domain.preference.dto;

import java.util.List;
import tools.jackson.databind.JsonNode;

public record UpdatePreferencesRequest(
    String optionsVersion,
    String budgetLevel,
    Integer availableTimeMinutes,
    String indoorOutdoor,
    String difficultyPreference,
    List<Integer> preferredCategoryIds,
    Integer expectedVersion,
    JsonNode questionnaire) {}
