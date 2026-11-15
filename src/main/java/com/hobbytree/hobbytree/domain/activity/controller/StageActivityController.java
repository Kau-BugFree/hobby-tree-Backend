package com.hobbytree.hobbytree.domain.activity.controller;

import com.hobbytree.hobbytree.domain.activity.code.ActivitySuccessCode;
import com.hobbytree.hobbytree.domain.activity.dto.StageActivityListResponse;
import com.hobbytree.hobbytree.domain.activity.service.StageActivityService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/hobby-stages")
public class StageActivityController {
  private final StageActivityService service;

  @GetMapping("/{stageId}/activities")
  public ResponseEntity<ApiResponse<StageActivityListResponse>> list(
      @AuthenticationPrincipal Long userId,
      @PathVariable @Positive Long stageId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                ActivitySuccessCode.STAGE_LIST, service.list(userId, stageId, cursor, size)));
  }
}
