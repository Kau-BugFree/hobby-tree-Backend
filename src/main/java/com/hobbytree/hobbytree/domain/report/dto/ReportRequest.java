package com.hobbytree.hobbytree.domain.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ReportRequest(
    @NotBlank String targetType,
    @NotNull @Positive Long targetId,
    @NotBlank String reason,
    @NotBlank String description) {}
