package com.hobbytree.hobbytree.domain.experience.dto;

import java.time.Instant;

public record CreateReservationResponse(
    Long reservationId,
    Long experienceId,
    Long sessionId,
    Long userHobbyId,
    String status,
    String verificationStatus,
    String externalReservationId,
    String bookingUrl,
    Instant createdAt) {}
