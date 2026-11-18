package com.hobbytree.hobbytree.domain.activity.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ActivityRecordSuccessCode implements BaseSuccessCode {
  STARTED(HttpStatus.CREATED, "RECORD201_1", "활동을 시작했습니다."),
  REUSED(HttpStatus.OK, "RECORD200_1", "진행 중인 활동을 조회했습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
