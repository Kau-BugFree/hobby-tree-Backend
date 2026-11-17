package com.hobbytree.hobbytree.domain.location.controller;

import com.hobbytree.hobbytree.domain.location.code.LocationSuccessCode;
import com.hobbytree.hobbytree.domain.location.dto.SetLocationRequest;
import com.hobbytree.hobbytree.domain.location.dto.SetLocationResponse;
import com.hobbytree.hobbytree.domain.location.service.UserLocationService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/location")
public class UserLocationController {
  private final UserLocationService service;

  @PutMapping
  public ResponseEntity<ApiResponse<SetLocationResponse>> set(
      @AuthenticationPrincipal Long userId, @RequestBody SetLocationRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(LocationSuccessCode.SET, service.set(userId, request)));
  }
}
