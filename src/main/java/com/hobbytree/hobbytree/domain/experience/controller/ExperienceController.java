package com.hobbytree.hobbytree.domain.experience.controller;

import com.hobbytree.hobbytree.domain.notification.code.NotificationSubscriptionSuccessCode;
import com.hobbytree.hobbytree.domain.notification.dto.ClassOpenSubscriptionResponse;
import com.hobbytree.hobbytree.domain.notification.service.NotificationSubscriptionService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/experiences")
public class ExperienceController {
  private final NotificationSubscriptionService service;

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
