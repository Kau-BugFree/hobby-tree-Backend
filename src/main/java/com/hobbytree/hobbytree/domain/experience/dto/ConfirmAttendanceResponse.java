package com.hobbytree.hobbytree.domain.experience.dto;

import java.time.Instant;

public record ConfirmAttendanceResponse(
    Long reservationId,
    Long attendanceId,
    boolean attended,
    Instant verifiedAt,
    Long recordId,
    Long activityId,
    Long userHobbyId,
    AttendanceGrowthResponse growth) {}
