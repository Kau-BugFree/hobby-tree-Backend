package com.hobbytree.hobbytree.domain.activity.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum StageErrorCode implements BaseErrorCode {
  NOT_FOUND(HttpStatus.NOT_FOUND, "STAGE404_1", "단계가 없거나 열람할 수 없음");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
