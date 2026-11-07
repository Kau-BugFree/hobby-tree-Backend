package com.hobbytree.hobbytree.domain.onboarding.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum OnboardingSuccessCode implements BaseSuccessCode {
  OPTIONS("ONBOARDING200_1", "온보딩 선택지를 조회했습니다.");
  private final HttpStatus status = HttpStatus.OK;
  private final String code;
  private final String message;
}
