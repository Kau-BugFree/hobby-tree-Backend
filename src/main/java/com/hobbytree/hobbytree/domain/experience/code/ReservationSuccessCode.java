package com.hobbytree.hobbytree.domain.experience.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ReservationSuccessCode implements BaseSuccessCode {
  CREATED(HttpStatus.CREATED, "RESERVATION201_1", "외부 예약 정보가 기록되었습니다."),
  LIST(HttpStatus.OK, "RESERVATION200_1", "내 예약 목록을 조회했습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
