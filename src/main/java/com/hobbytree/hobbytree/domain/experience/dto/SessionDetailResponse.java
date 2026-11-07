package com.hobbytree.hobbytree.domain.experience.dto;

import java.time.Instant;

public record SessionDetailResponse(
    Long sessionId, Instant startsAt, Instant endsAt, String status, Integer remainingSeats) {}
