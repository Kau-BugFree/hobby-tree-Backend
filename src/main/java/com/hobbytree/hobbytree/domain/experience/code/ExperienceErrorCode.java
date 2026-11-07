package com.hobbytree.hobbytree.domain.experience.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ExperienceErrorCode implements BaseErrorCode {
  NOT_FOUND(HttpStatus.NOT_FOUND, "EXPERIENCE404_1", "클래스를 찾을 수 없음"),
  INVALID_FILTER(HttpStatus.BAD_REQUEST, "EXPERIENCE400_1", "날짜 범위·필터·커서가 올바르지 않음");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
