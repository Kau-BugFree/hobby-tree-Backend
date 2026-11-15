package com.hobbytree.hobbytree.domain.experience.controller;

import com.hobbytree.hobbytree.domain.experience.code.ExperienceSuccessCode;
import com.hobbytree.hobbytree.domain.experience.code.ReservationSuccessCode;
import com.hobbytree.hobbytree.domain.experience.dto.CreateReservationRequest;
import com.hobbytree.hobbytree.domain.experience.dto.CreateReservationResponse;
import com.hobbytree.hobbytree.domain.experience.dto.ExperienceDetailResponse;
import com.hobbytree.hobbytree.domain.experience.dto.ExperienceListResponse;
import com.hobbytree.hobbytree.domain.experience.service.ExperienceReservationService;
import com.hobbytree.hobbytree.domain.experience.service.ExperienceService;
import com.hobbytree.hobbytree.domain.notification.code.NotificationSubscriptionSuccessCode;
import com.hobbytree.hobbytree.domain.notification.dto.ClassOpenSubscriptionResponse;
import com.hobbytree.hobbytree.domain.notification.service.NotificationSubscriptionService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/experiences")
public class ExperienceController {
  private final NotificationSubscriptionService service;
  private final ExperienceService experiences;
  private final ExperienceReservationService reservations;

  @GetMapping
  public ResponseEntity<ApiResponse<ExperienceListResponse>> list(
      @AuthenticationPrincipal Long userId,
      @RequestParam(required = false) Long hobbyId,
      @RequestParam(required = false) Long regionId,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String fromDate,
      @RequestParam(required = false) String toDate,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                ExperienceSuccessCode.LIST,
                experiences.list(
                    userId, hobbyId, regionId, type, status, fromDate, toDate, sort, cursor,
                    size)));
  }

  @GetMapping("/{experienceId}")
  public ResponseEntity<ApiResponse<ExperienceDetailResponse>> detail(
      @AuthenticationPrincipal Long userId, @PathVariable @Positive Long experienceId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                ExperienceSuccessCode.DETAIL, experiences.detail(userId, experienceId)));
  }

  @PostMapping("/{experienceId}/reservations")
  public ResponseEntity<ApiResponse<CreateReservationResponse>> createReservation(
      @AuthenticationPrincipal Long userId,
      @PathVariable @Positive Long experienceId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody CreateReservationRequest request) {
    return ResponseEntity.status(ReservationSuccessCode.CREATED.getStatus())
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                ReservationSuccessCode.CREATED,
                reservations.create(userId, experienceId, idempotencyKey, request)));
  }

  @PostMapping("/{experienceId}/open-notification")
  public ResponseEntity<ApiResponse<ClassOpenSubscriptionResponse>> subscribeToClassOpen(
      @AuthenticationPrincipal Long userId, @PathVariable @Positive Long experienceId) {
    var outcome = service.subscribeToClassOpen(userId, experienceId);
    var code =
        outcome.created()
            ? NotificationSubscriptionSuccessCode.CLASS_OPEN_SUBSCRIBED
            : NotificationSubscriptionSuccessCode.CLASS_OPEN_ALREADY_SUBSCRIBED;
    return ResponseEntity.status(code.getStatus())
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(code, outcome.body()));
  }
}
