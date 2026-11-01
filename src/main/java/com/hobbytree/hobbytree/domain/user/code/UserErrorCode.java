package com.hobbytree.hobbytree.domain.user.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum UserErrorCode implements BaseErrorCode {
  WITHDRAWAL_CONFLICT(HttpStatus.CONFLICT, "USER409_1", "탈퇴 처리 중 상태 충돌");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
