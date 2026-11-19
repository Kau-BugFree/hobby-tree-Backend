package com.hobbytree.hobbytree.domain.activity.controller;

import com.hobbytree.hobbytree.domain.activity.code.ActivityRecordSuccessCode;
import com.hobbytree.hobbytree.domain.activity.dto.StartActivityRequest;
import com.hobbytree.hobbytree.domain.activity.dto.StartActivityResponse;
import com.hobbytree.hobbytree.domain.activity.service.ActivityRecordStartService;
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
@RequestMapping("/api/v1/user-hobbies/{userHobbyId}/activity-records")
public class ActivityRecordStartController {
  private final ActivityRecordStartService service;

  @PostMapping
  public ResponseEntity<ApiResponse<StartActivityResponse>> start(
      @AuthenticationPrincipal Long userId,
      @PathVariable @Positive Long userHobbyId,
      @Valid @RequestBody StartActivityRequest request) {
    var result = service.start(userId, userHobbyId, request);
    var code =
        result.reused() ? ActivityRecordSuccessCode.REUSED : ActivityRecordSuccessCode.STARTED;
    return ResponseEntity.status(code.getStatus())
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(code, result));
  }
}
