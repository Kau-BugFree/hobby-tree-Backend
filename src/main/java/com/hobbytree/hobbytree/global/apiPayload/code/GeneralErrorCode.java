package com.hobbytree.hobbytree.global.apiPayload.code;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum GeneralErrorCode implements BaseErrorCode {
  COMMON_BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON400_1", "잘못된 요청입니다."),
  COMMON_FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON403_1", "접근 권한이 없습니다."),
  COMMON_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON404_1", "요청한 경로를 찾을 수 없습니다."),
  COMMON_METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON405_1", "지원하지 않는 요청 메서드입니다."),
  COMMON_PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "COMMON413_1", "요청 크기 제한을 초과했습니다."),
  COMMON_UNSUPPORTED_MEDIA_TYPE(
      HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON415_1", "지원하지 않는 콘텐츠 타입입니다."),
  // API-14's Idempotency-Key handling (IdempotencyKeyService) - generic, not recommendation-
  // specific, hence COMMON rather than a domain-prefixed code.
  IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "COMMON409_1", "같은 멱등 키를 다른 요청 내용에 재사용"),
  IDEMPOTENCY_KEY_IN_PROGRESS(HttpStatus.CONFLICT, "COMMON409_2", "같은 멱등 키의 요청이 아직 처리 중"),
  // #268: shared across every domain's RateLimiter, not just auth's own Redis-based checks.
  TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "COMMON429_1", "호출 제한 초과; Retry-After 헤더 참고"),
  COMMON_INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON500_1", "서버 오류입니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
