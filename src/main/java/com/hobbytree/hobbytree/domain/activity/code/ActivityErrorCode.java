package com.hobbytree.hobbytree.domain.activity.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ActivityErrorCode implements BaseErrorCode {
  NOT_SAVABLE(HttpStatus.NOT_FOUND, "ACTIVITY404_1", "활동이 없거나 저장할 수 없음");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
