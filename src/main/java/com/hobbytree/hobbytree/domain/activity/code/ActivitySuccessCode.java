package com.hobbytree.hobbytree.domain.activity.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ActivitySuccessCode implements BaseSuccessCode {
  STAGE_LIST(HttpStatus.OK, "ACTIVITY200_2", "단계별 활동 목록을 조회했습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
