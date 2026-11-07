package com.hobbytree.hobbytree.domain.experience.dto;

public record CreateReservationRequest(
    Long sessionId, Long userHobbyId, String externalReservationId) {}
