package com.hobbytree.hobbytree.domain.activity.controller;

import com.hobbytree.hobbytree.domain.activity.code.ActivityRecordSuccessCode;
import com.hobbytree.hobbytree.domain.activity.dto.DeleteActivityRecordResponse;
import com.hobbytree.hobbytree.domain.activity.service.ActivityRecordDeletionService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/activity-records")
public class ActivityRecordDeletionController {
  private final ActivityRecordDeletionService service;

  @DeleteMapping("/{recordId}")
  public ResponseEntity<ApiResponse<DeleteActivityRecordResponse>> delete(
      @AuthenticationPrincipal Long userId, @PathVariable @Positive Long recordId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                ActivityRecordSuccessCode.DELETED, service.delete(userId, recordId)));
  }
}
