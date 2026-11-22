package com.hobbytree.hobbytree.domain.activity.controller;

import com.hobbytree.hobbytree.domain.activity.code.ActivityRecordSuccessCode;
import com.hobbytree.hobbytree.domain.activity.dto.CompleteActivityRequest;
import com.hobbytree.hobbytree.domain.activity.dto.CompleteActivityResponse;
import com.hobbytree.hobbytree.domain.activity.service.ActivityRecordCompletionService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/activity-records")
public class ActivityRecordCompletionController {
  private final ActivityRecordCompletionService service;

  @PatchMapping("/{recordId}/complete")
  public ResponseEntity<ApiResponse<CompleteActivityResponse>> complete(
      @AuthenticationPrincipal Long userId,
      @PathVariable @Positive Long recordId,
      @Valid @RequestBody CompleteActivityRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                ActivityRecordSuccessCode.COMPLETED, service.complete(userId, recordId, request)));
  }
}
