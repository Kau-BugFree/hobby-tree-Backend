package com.hobbytree.hobbytree.domain.onboarding.dto;

import com.hobbytree.hobbytree.domain.hobby.dto.HobbyCategoryResponse;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record OnboardingOptionsResponse(
    String optionsVersion,
    List<BudgetOptionResponse> budgetOptions,
    List<AvailableTimeOptionResponse> availableTimeOptions,
    List<IndoorOutdoorOptionResponse> indoorOutdoorOptions,
    List<DifficultyOptionResponse> difficultyOptions,
    List<HobbyCategoryResponse> categories,
    List<TermsResponse> terms,
    LocationConsentNoticeResponse locationConsentNotice,
    JsonNode questionnaire) {}
