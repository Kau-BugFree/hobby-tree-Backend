package com.hobbytree.hobbytree.domain.activity.controller;

import com.hobbytree.hobbytree.domain.activity.code.ActivityRecordSuccessCode;
import com.hobbytree.hobbytree.domain.activity.dto.ActivityRecordListResponse;
import com.hobbytree.hobbytree.domain.activity.service.ActivityRecordListService;
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
@RequestMapping("/api/v1/user-hobbies")
public class ActivityRecordListController {
  private final ActivityRecordListService service;

  @GetMapping("/{userHobbyId}/activity-records")
  public ResponseEntity<ApiResponse<ActivityRecordListResponse>> list(
      @AuthenticationPrincipal Long userId,
      @PathVariable @Positive Long userHobbyId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                ActivityRecordSuccessCode.LIST,
                service.list(userId, userHobbyId, status, cursor, size)));
  }
}
