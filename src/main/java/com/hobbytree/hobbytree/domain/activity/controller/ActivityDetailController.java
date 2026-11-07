package com.hobbytree.hobbytree.domain.activity.controller;

import com.hobbytree.hobbytree.domain.activity.code.ActivitySuccessCode;
import com.hobbytree.hobbytree.domain.activity.dto.ActivityDetailResponse;
import com.hobbytree.hobbytree.domain.activity.service.ActivityDetailService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/activities")
public class ActivityDetailController {
  private final ActivityDetailService service;

  @GetMapping("/{activityId}")
  public ResponseEntity<ApiResponse<ActivityDetailResponse>> detail(
      @AuthenticationPrincipal Long userId, @PathVariable @Positive Long activityId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(ActivitySuccessCode.DETAIL, service.detail(userId, activityId)));
  }
}
