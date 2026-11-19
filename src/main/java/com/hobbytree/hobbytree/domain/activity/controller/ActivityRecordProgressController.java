package com.hobbytree.hobbytree.domain.activity.controller;

import com.hobbytree.hobbytree.domain.activity.code.ActivityRecordSuccessCode;
import com.hobbytree.hobbytree.domain.activity.dto.SaveActivityProgressRequest;
import com.hobbytree.hobbytree.domain.activity.dto.SaveActivityProgressResponse;
import com.hobbytree.hobbytree.domain.activity.service.ActivityRecordProgressService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/activity-records")
public class ActivityRecordProgressController {
  private final ActivityRecordProgressService service;

  @PatchMapping("/{recordId}/progress")
  public ResponseEntity<ApiResponse<SaveActivityProgressResponse>> save(
      @AuthenticationPrincipal Long userId,
      @PathVariable @Positive Long recordId,
      @Valid @RequestBody SaveActivityProgressRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                ActivityRecordSuccessCode.PROGRESS_SAVED, service.save(userId, recordId, request)));
  }
}
