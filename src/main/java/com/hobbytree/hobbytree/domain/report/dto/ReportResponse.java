package com.hobbytree.hobbytree.domain.report.dto;

import java.time.Instant;

public record ReportResponse(
    Long reportId, String targetType, Long targetId, String status, Instant createdAt) {}
