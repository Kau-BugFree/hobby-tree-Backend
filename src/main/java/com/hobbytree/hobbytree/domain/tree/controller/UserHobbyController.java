package com.hobbytree.hobbytree.domain.tree.controller;

import com.hobbytree.hobbytree.domain.tree.code.TreeSuccessCode;
import com.hobbytree.hobbytree.domain.tree.dto.PlantUserHobbyRequest;
import com.hobbytree.hobbytree.domain.tree.dto.PlantUserHobbyResponse;
import com.hobbytree.hobbytree.domain.tree.dto.UserHobbyListResponse;
import com.hobbytree.hobbytree.domain.tree.service.UserHobbyService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/user-hobbies")
public class UserHobbyController {
  private final UserHobbyService service;

  @PostMapping
  public ResponseEntity<ApiResponse<PlantUserHobbyResponse>> plant(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody PlantUserHobbyRequest request) {
    return ResponseEntity.status(TreeSuccessCode.CREATE.getStatus())
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(TreeSuccessCode.CREATE, service.plant(userId, request)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<UserHobbyListResponse>> list(
      @AuthenticationPrincipal Long userId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                TreeSuccessCode.LIST, service.list(userId, status, cursor, size)));
  }
}
