package com.hobbytree.hobbytree.domain.experience.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ExperienceErrorCode implements BaseErrorCode {
  NOT_FOUND(HttpStatus.NOT_FOUND, "EXPERIENCE404_1", "클래스를 찾을 수 없음"),
  INVALID_FILTER(HttpStatus.BAD_REQUEST, "EXPERIENCE400_1", "날짜 범위·필터·커서가 올바르지 않음"),
  // API-36: same code value as NOT_FOUND but a different message - API.md documents
  // EXPERIENCE404_1 with endpoint-specific text ("클래스·모임이 없거나 비공개 상태임" here vs
  // API-40's "클래스를 찾을 수 없음"), so this is a separate constant, not a reuse.
  DETAIL_NOT_FOUND(HttpStatus.NOT_FOUND, "EXPERIENCE404_1", "클래스·모임이 없거나 비공개 상태임");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
