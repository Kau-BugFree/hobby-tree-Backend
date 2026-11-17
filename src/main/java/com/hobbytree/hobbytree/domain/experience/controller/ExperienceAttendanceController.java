package com.hobbytree.hobbytree.domain.experience.controller;

import com.hobbytree.hobbytree.domain.experience.code.AttendanceSuccessCode;
import com.hobbytree.hobbytree.domain.experience.dto.ConfirmAttendanceRequest;
import com.hobbytree.hobbytree.domain.experience.dto.ConfirmAttendanceResponse;
import com.hobbytree.hobbytree.domain.experience.service.ExperienceAttendanceService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/experience-reservations")
public class ExperienceAttendanceController {
  private final ExperienceAttendanceService attendances;

  @PostMapping("/{reservationId}/attendance")
  public ResponseEntity<ApiResponse<ConfirmAttendanceResponse>> confirm(
      @AuthenticationPrincipal Long userId,
      @PathVariable @Positive Long reservationId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody ConfirmAttendanceRequest request) {
    return ResponseEntity.status(AttendanceSuccessCode.CONFIRMED.getStatus())
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                AttendanceSuccessCode.CONFIRMED,
                attendances.confirm(userId, reservationId, idempotencyKey, request)));
  }
}
