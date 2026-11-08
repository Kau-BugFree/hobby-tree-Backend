package com.hobbytree.hobbytree.domain.experience.dto;

import java.time.Instant;

public record MyReservationItemResponse(
    Long reservationId,
    Long experienceId,
    String title,
    Long sessionId,
    Instant startsAt,
    Long userHobbyId,
    String status,
    String verificationStatus,
    boolean attended,
    String bookingUrl,
    String cancellationPolicyUrl,
    Instant createdAt) {}
