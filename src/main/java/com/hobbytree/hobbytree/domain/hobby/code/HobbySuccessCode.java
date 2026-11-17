package com.hobbytree.hobbytree.domain.hobby.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum HobbySuccessCode implements BaseSuccessCode {
  LIST("HOBBY200_1", "취미 목록을 조회했습니다."),
  DETAIL("HOBBY200_2", "취미 상세를 조회했습니다."),
  STAGES("ACTIVITY200_1", "취미 단계 목록을 조회했습니다.");
  private final HttpStatus status = HttpStatus.OK;
  private final String code;
  private final String message;
}
