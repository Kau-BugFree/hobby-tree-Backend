package com.hobbytree.hobbytree.domain.location.dto;

public record SetLocationRequest(
    String source, Long regionId, Double latitude, Double longitude, String consentVersion) {}
