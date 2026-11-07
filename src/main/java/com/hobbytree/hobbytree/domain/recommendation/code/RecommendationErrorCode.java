package com.hobbytree.hobbytree.domain.recommendation.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum RecommendationErrorCode implements BaseErrorCode {
  NOT_RECOMMENDED(HttpStatus.NOT_FOUND, "RECOMMENDATION404_1", "본인에게 해당 취미를 추천한 결과가 없음");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
