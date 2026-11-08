package com.hobbytree.hobbytree.domain.experience.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ReservationErrorCode implements BaseErrorCode {
  // API-39: 본인 소유의 예약을 찾을 수 없음 (존재하지 않거나 다른 회원의 예약).
  NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION404_1", "본인 소유의 예약을 찾을 수 없음"),
  HOBBY_MISMATCH(HttpStatus.BAD_REQUEST, "RESERVATION400_1", "나무와 클래스의 취미 불일치"),
  NOT_BOOKABLE(HttpStatus.CONFLICT, "RESERVATION409_1", "마감·취소된 일정 또는 이미 연결된 외부 예약"),
  // Declared for contract completeness; never thrown by this implementation - there is no
  // real external provider integration to fail (hobbytree-team-decisions-2026-11-04: no real
  // partner/provider integration for MVP), the same posture API-14 Part 1 takes with AI503_1.
  INTEGRATION_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "RESERVATION503_1", "필수 외부 예약 연동을 일시적으로 사용할 수 없음");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
