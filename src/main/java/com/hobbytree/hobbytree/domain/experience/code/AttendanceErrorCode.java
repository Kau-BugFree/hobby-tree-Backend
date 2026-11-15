package com.hobbytree.hobbytree.domain.experience.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum AttendanceErrorCode implements BaseErrorCode {
  HOBBY_MISMATCH(HttpStatus.BAD_REQUEST, "ATTENDANCE400_1", "예약·활동·나무의 취미가 일치하지 않음"),
  // Declared for contract completeness; no real provider signs or confirms attendance tokens
  // today, so this codepath is never actually reached in this implementation - see
  // ExperienceAttendanceService.verifyAttendanceToken's own 503 for what always happens first.
  VERIFICATION_FAILED(HttpStatus.FORBIDDEN, "ATTENDANCE403_1", "참여 증명의 서명·예약 귀속·유효기간 검증 실패"),
  NOT_CONFIRMED(HttpStatus.CONFLICT, "ATTENDANCE409_1", "예약 미확정·취소·다른 나무에 이미 인정됨"),
  NO_ACTIVITY_MAPPING(HttpStatus.UNPROCESSABLE_ENTITY, "ATTENDANCE422_1", "해당 클래스의 활동 인정 매핑이 없음"),
  // No real provider callback/verification API exists anywhere in the current API set (same
  // posture as ReservationErrorCode.INTEGRATION_UNAVAILABLE) - API-39's own 비고 requires
  // treating unverifiable attendance as unsuccessful, never as a client-trusted attended:true.
  VERIFICATION_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "ATTENDANCE503_1", "참여 사실을 확인할 외부 연동을 사용할 수 없음");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
