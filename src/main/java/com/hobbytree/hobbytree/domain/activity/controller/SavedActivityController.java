package com.hobbytree.hobbytree.domain.activity.controller;

import com.hobbytree.hobbytree.domain.activity.code.SavedActivitySuccessCode;
import com.hobbytree.hobbytree.domain.activity.dto.SaveActivityResponse;
import com.hobbytree.hobbytree.domain.activity.dto.UnsaveActivityResponse;
import com.hobbytree.hobbytree.domain.activity.service.SavedActivityService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/activities")
public class SavedActivityController {
  private final SavedActivityService service;

  @PutMapping("/{activityId}/save")
  public ResponseEntity<ApiResponse<SaveActivityResponse>> save(
      @AuthenticationPrincipal Long userId, @PathVariable @Positive Long activityId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                SavedActivitySuccessCode.SAVED, service.save(userId, activityId)));
  }

  @DeleteMapping("/{activityId}/save")
  public ResponseEntity<ApiResponse<UnsaveActivityResponse>> unsave(
      @AuthenticationPrincipal Long userId, @PathVariable @Positive Long activityId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                SavedActivitySuccessCode.UNSAVED, service.unsave(userId, activityId)));
  }
}
