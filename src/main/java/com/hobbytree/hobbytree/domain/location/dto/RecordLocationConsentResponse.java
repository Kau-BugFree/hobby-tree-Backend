package com.hobbytree.hobbytree.domain.location.dto;

import java.time.Instant;

public record RecordLocationConsentResponse(
    Long consentId,
    String consentType,
    String version,
    boolean agreed,
    Instant recordedAt,
    boolean locationCleared) {}
