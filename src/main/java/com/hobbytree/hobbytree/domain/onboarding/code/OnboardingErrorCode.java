package com.hobbytree.hobbytree.domain.onboarding.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum OnboardingErrorCode implements BaseErrorCode {
  REQUIRED_AGREEMENT_OR_PROFILE_MISSING(
      HttpStatus.BAD_REQUEST, "ONBOARDING400_1", "필수 약관 미동의 또는 필수 프로필 누락"),
  VERSION_CONFLICT(HttpStatus.CONFLICT, "ONBOARDING409_1", "취향 미저장·버전 불일치·약관 버전 변경");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
