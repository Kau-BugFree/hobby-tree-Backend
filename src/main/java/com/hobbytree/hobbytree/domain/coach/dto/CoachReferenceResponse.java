package com.hobbytree.hobbytree.domain.coach.dto;

// Numeric sourceId only exists for ACTIVITY. Other sources retain their exact AI code.
public record CoachReferenceResponse(
    String sourceType, Long sourceId, String title, String sourceCode) {}
