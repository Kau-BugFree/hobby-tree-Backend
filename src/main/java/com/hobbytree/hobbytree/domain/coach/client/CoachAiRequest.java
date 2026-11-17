package com.hobbytree.hobbytree.domain.coach.client;

public record CoachAiRequest(
    Long conversationId, String message, String requestId, String hobbyCode) {}
