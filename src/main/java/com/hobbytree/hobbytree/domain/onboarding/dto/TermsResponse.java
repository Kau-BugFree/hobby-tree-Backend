package com.hobbytree.hobbytree.domain.onboarding.dto;

public record TermsResponse(
    int termsId, String title, String version, boolean isRequired, String content) {}
