package com.hobbytree.hobbytree.domain.itemrecommendation.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ItemRecommendationErrorCode implements BaseErrorCode {
  INVALID_SELECTION(HttpStatus.BAD_REQUEST, "ITEMREC400_1", "준비물 선택 조건이 유효하지 않음"),
  TOO_MANY_MATERIALS(HttpStatus.BAD_REQUEST, "ITEMREC400_2", "활동 준비물이 처리 가능한 개수를 초과함"),
  CONTENT_VERSION_CHANGED(HttpStatus.CONFLICT, "ITEMREC409_1", "활동 내용이 변경되어 최신 상세 정보를 다시 조회해야 함"),
  AI_BAD_RESPONSE(HttpStatus.BAD_GATEWAY, "ITEMREC502_1", "준비물 추천 서비스 응답을 확인할 수 없음"),
  AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "ITEMREC503_1", "준비물 추천 서비스를 일시적으로 사용할 수 없음"),
  AI_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "ITEMREC504_1", "준비물 추천 응답 시간이 초과되었습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
