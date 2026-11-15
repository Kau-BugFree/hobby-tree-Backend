package com.hobbytree.hobbytree.domain.onboarding.controller;

import com.hobbytree.hobbytree.domain.onboarding.code.OnboardingSuccessCode;
import com.hobbytree.hobbytree.domain.onboarding.dto.CompleteOnboardingRequest;
import com.hobbytree.hobbytree.domain.onboarding.dto.CompleteOnboardingResponse;
import com.hobbytree.hobbytree.domain.onboarding.dto.OnboardingOptionsResponse;
import com.hobbytree.hobbytree.domain.onboarding.service.OnboardingService;
import com.hobbytree.hobbytree.domain.user.service.UserService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {
  private final OnboardingService service;
  private final UserService userService;

  // Unauthenticated by design ("선택지와 약관은 로그인 전에도 조회할 수 있다") - no
  // @AuthenticationPrincipal here, and the path is in SecurityConfig's permitAll list.
  @GetMapping("/options")
  public ResponseEntity<ApiResponse<OnboardingOptionsResponse>> options() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(OnboardingSuccessCode.OPTIONS, service.options()));
  }

  @PostMapping("/complete")
  public ResponseEntity<ApiResponse<CompleteOnboardingResponse>> complete(
      @AuthenticationPrincipal Long userId, @RequestBody CompleteOnboardingRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                OnboardingSuccessCode.COMPLETE, userService.completeOnboarding(userId, request)));
  }
}
