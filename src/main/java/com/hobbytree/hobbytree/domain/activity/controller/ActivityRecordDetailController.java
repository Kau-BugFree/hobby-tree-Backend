package com.hobbytree.hobbytree.domain.activity.controller;

import com.hobbytree.hobbytree.domain.activity.code.ActivityRecordSuccessCode;
import com.hobbytree.hobbytree.domain.activity.dto.ActivityRecordDetailResponse;
import com.hobbytree.hobbytree.domain.activity.service.ActivityRecordDetailService;
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
@RequestMapping("/api/v1/activity-records")
public class ActivityRecordDetailController {
  private final ActivityRecordDetailService service;

  @GetMapping("/{recordId}")
  public ResponseEntity<ApiResponse<ActivityRecordDetailResponse>> detail(
      @AuthenticationPrincipal Long userId, @PathVariable @Positive Long recordId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                ActivityRecordSuccessCode.DETAIL, service.detail(userId, recordId)));
  }
}
