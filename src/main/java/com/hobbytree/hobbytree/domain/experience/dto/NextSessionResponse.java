package com.hobbytree.hobbytree.domain.experience.dto;

import java.time.Instant;

public record NextSessionResponse(Long sessionId, Instant startsAt, Integer durationMinutes) {}
