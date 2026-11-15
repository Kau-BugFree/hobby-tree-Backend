package com.hobbytree.hobbytree.domain.experience.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ExperienceSuccessCode implements BaseSuccessCode {
  LIST(HttpStatus.OK, "EXPERIENCE200_1", "클래스·모임 목록을 조회했습니다."),
  DETAIL(HttpStatus.OK, "EXPERIENCE200_2", "클래스·모임 상세를 조회했습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
