package com.hobbytree.hobbytree.domain.auth.controller;

import com.hobbytree.hobbytree.domain.auth.code.AuthSuccessCode;
import com.hobbytree.hobbytree.domain.auth.dto.*;
import com.hobbytree.hobbytree.domain.auth.service.LogoutService;
import com.hobbytree.hobbytree.domain.auth.service.SocialLoginService;
import com.hobbytree.hobbytree.domain.auth.service.TokenReissueService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final SocialLoginService service;
  private final TokenReissueService reissueService;
  private final LogoutService logoutService;

  @PostMapping("/logout")
  public ResponseEntity<ApiResponse<LogoutResponse>> logout(
      @Valid @RequestBody LogoutRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(AuthSuccessCode.LOGOUT, logoutService.logout(request)));
  }

  @PostMapping("/reissue")
  public ResponseEntity<ApiResponse<ReissueResponse>> reissue(
      @Valid @RequestBody ReissueRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(AuthSuccessCode.REISSUE, reissueService.reissue(request)));
  }

  @PostMapping("/social-login")
  public ResponseEntity<ApiResponse<SocialLoginResponse>> login(
      @Valid @RequestBody SocialLoginRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(AuthSuccessCode.SOCIAL_LOGIN, service.login(request)));
  }
}
