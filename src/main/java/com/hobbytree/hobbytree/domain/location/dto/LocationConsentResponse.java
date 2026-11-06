package com.hobbytree.hobbytree.domain.location.dto;

import java.time.Instant;

public record LocationConsentResponse(
    String consentType, String version, boolean agreed, Instant recordedAt) {}
