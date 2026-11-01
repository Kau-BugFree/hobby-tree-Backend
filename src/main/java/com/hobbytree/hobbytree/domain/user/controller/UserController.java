package com.hobbytree.hobbytree.domain.user.controller;

import com.hobbytree.hobbytree.domain.user.code.UserSuccessCode;
import com.hobbytree.hobbytree.domain.user.dto.WithdrawResponse;
import com.hobbytree.hobbytree.domain.user.service.UserService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {
  private final UserService service;

  @DeleteMapping("/me")
  public ResponseEntity<ApiResponse<WithdrawResponse>> withdraw(
      @AuthenticationPrincipal Long userId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(UserSuccessCode.WITHDRAW, service.withdraw(userId)));
  }
}
