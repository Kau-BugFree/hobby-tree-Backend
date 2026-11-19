package com.hobbytree.hobbytree.domain.coach.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum CoachErrorCode implements BaseErrorCode {
  INVALID_MESSAGE(HttpStatus.BAD_REQUEST, "COACH400_1", "질문 형식·길이 오류"),
  CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "COACH404_1", "본인 소유의 대화를 찾을 수 없음"),
  UNSUPPORTED_HOBBY(HttpStatus.BAD_REQUEST, "COACH400_2", "현재 코치는 요리 취미만 지원합니다."),
  TURN_IN_PROGRESS(HttpStatus.CONFLICT, "COACH409_1", "이전 질문이 처리 중입니다. 같은 요청 키로 재시도해 주세요."),
  AI_BAD_RESPONSE(HttpStatus.BAD_GATEWAY, "COACH502_1", "코치 응답을 확인할 수 없습니다."),
  AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "COACH503_1", "코치 서비스를 일시적으로 사용할 수 없습니다."),
  AI_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "COACH504_1", "코치 응답 시간이 초과되었습니다. 같은 요청 키로 재시도해 주세요.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
