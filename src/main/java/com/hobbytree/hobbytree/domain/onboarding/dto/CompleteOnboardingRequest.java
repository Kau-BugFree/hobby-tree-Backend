package com.hobbytree.hobbytree.domain.onboarding.dto;

import java.util.List;

public record CompleteOnboardingRequest(
    Integer preferencesVersion, List<AgreementRequest> agreements) {}
