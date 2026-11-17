package com.hobbytree.hobbytree.domain.experience.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum AttendanceSuccessCode implements BaseSuccessCode {
  CONFIRMED(HttpStatus.OK, "ATTENDANCE200_1", "참여가 확인되고 활동이 인정되었습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
