package com.hobbytree.hobbytree.domain.experience.dto;

public record ActivityRecognitionResponse(
    boolean supported, Long activityId, boolean verificationRequired) {}
