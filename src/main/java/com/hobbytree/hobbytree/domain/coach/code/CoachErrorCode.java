package com.hobbytree.hobbytree.domain.coach.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum CoachErrorCode implements BaseErrorCode {
  INVALID_MESSAGE(HttpStatus.BAD_REQUEST, "COACH400_1", "질문 형식·길이 오류"),
  CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "COACH404_1", "본인 소유의 대화를 찾을 수 없음");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
