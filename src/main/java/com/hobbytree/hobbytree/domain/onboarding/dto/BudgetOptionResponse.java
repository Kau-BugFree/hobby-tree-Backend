package com.hobbytree.hobbytree.domain.onboarding.dto;

public record BudgetOptionResponse(
    String value, String label, int monthlyMin, Integer monthlyMax) {}
