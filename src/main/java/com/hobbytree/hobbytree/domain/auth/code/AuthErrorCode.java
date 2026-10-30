package com.hobbytree.hobbytree.domain.auth.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum AuthErrorCode implements BaseErrorCode {
  SOCIAL_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH401_1", "소셜 토큰이 만료·위조되었거나 서비스 앱용 토큰이 아님"),
  ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH401_2", "유효하지 않은 서비스 인증 토큰입니다."),
  REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH401_3", "Refresh Token 만료·불일치·폐기·재사용"),
  SUSPENDED(HttpStatus.FORBIDDEN, "AUTH403_1", "정지된 회원"),
  WITHDRAWN_MEMBER(HttpStatus.FORBIDDEN, "AUTH403_3", "탈퇴한 회원"),
  WITHDRAWN(HttpStatus.CONFLICT, "AUTH409_1", "탈퇴한 계정입니다. 복구 후 이용할 수 있습니다."),
  PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "AUTH502_1", "소셜 제공자 응답 오류"),
  PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AUTH504_1", "소셜 제공자 검증 시간 초과"),
  RATE_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "COMMON429_1", "호출 제한 초과; Retry-After 헤더 참고");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
