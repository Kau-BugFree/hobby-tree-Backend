package com.hobbytree.hobbytree.domain.recommendation.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum RecommendationErrorCode implements BaseErrorCode {
  AI_BAD_RESPONSE(HttpStatus.BAD_GATEWAY, "AI502_1", "추천 서비스 응답을 확인할 수 없음"),
  AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI503_1", "추천 서비스를 일시적으로 사용할 수 없음"),
  AI_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI504_1", "추천 서비스 응답 시간 초과"),
  NOT_RECOMMENDED(HttpStatus.NOT_FOUND, "RECOMMENDATION404_1", "본인에게 해당 취미를 추천한 결과가 없음"),
  // API-15: same code value as NOT_RECOMMENDED but a different message - API.md documents
  // RECOMMENDATION404_1 with endpoint-specific text ("생성된 추천 결과가 없음" here vs API-19/20's
  // "본인에게 해당 취미를 추천한 결과가 없음"), so this is a separate constant, not a reuse.
  LATEST_NOT_FOUND(HttpStatus.NOT_FOUND, "RECOMMENDATION404_1", "생성된 추천 결과가 없음"),
  VERSION_CONFLICT(HttpStatus.CONFLICT, "RECOMMENDATION409_1", "취향 설정 버전 변경 또는 설정이 준비되지 않음"),
  NO_CANDIDATES(HttpStatus.UNPROCESSABLE_ENTITY, "RECOMMENDATION422_1", "조건을 충족하는 추천 가능 취미가 없음");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
