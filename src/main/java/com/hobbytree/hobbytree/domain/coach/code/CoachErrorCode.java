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
  AI_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "COACH504_1", "코치 응답 시간이 초과되었습니다. 같은 요청 키로 재시도해 주세요."),
  STT_INVALID_AUDIO(HttpStatus.BAD_REQUEST, "COACH400_3", "음성을 읽을 수 없습니다. 60초 이하의 음성을 확인해 주세요."),
  STT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "COACH413_1", "음성 파일은 7MiB 이하여야 합니다."),
  STT_UNSUPPORTED_MEDIA(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COACH415_1", "지원하는 음성 형식으로 녹음해 주세요."),
  STT_NO_SPEECH(HttpStatus.UNPROCESSABLE_ENTITY, "COACH422_1", "말소리를 알아듣지 못했습니다."),
  STT_BAD_RESPONSE(HttpStatus.BAD_GATEWAY, "COACH502_2", "음성 변환 응답을 확인할 수 없습니다."),
  STT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "COACH503_2", "음성 변환 서비스를 일시적으로 사용할 수 없습니다."),
  STT_TIMEOUT(
      HttpStatus.GATEWAY_TIMEOUT, "COACH504_2", "음성 변환 시간이 초과되었습니다. 다시 시도하면 새 변환 요청이 발생합니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
