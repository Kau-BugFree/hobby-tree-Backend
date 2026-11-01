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
  LOGOUT_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH401_3", "Refresh Token 위조 또는 발급 세션을 확인할 수 없음"),
  RESTORE_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH401_4", "복구 토큰 만료·위조·사용 완료"),
  SUSPENDED(HttpStatus.FORBIDDEN, "AUTH403_1", "정지된 회원"),
  WITHDRAWN_MEMBER(HttpStatus.FORBIDDEN, "AUTH403_3", "탈퇴한 회원"),
  WITHDRAWN(HttpStatus.CONFLICT, "AUTH409_1", "탈퇴한 계정입니다. 복구 후 이용할 수 있습니다."),
  NOT_RESTORABLE(HttpStatus.CONFLICT, "AUTH409_2", "복구 대상 상태가 아니거나 이미 복구됨"),
  RESTORE_WINDOW_EXPIRED(HttpStatus.GONE, "AUTH410_1", "복구 기간 만료 또는 영구 삭제로 복구할 수 없음"),
  PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "AUTH502_1", "소셜 제공자 응답 오류"),
  PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AUTH504_1", "소셜 제공자 검증 시간 초과"),
  RATE_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "COMMON429_1", "호출 제한 초과; Retry-After 헤더 참고");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
