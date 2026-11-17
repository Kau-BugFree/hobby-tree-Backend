package com.hobbytree.hobbytree.domain.user.dto;

public record QuietHoursResponse(boolean enabled, String start, String end, String timeZone) {}
