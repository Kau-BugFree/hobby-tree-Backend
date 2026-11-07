package com.hobbytree.hobbytree.domain.recommendation.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum RecommendationSuccessCode implements BaseSuccessCode {
  CREATED(HttpStatus.CREATED, "RECOMMENDATION201_1", "맞춤 취미 추천이 생성되었습니다."),
  LATEST(HttpStatus.OK, "RECOMMENDATION200_1", "최근 추천 결과를 조회했습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
