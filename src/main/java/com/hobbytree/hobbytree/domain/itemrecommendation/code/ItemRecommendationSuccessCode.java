package com.hobbytree.hobbytree.domain.itemrecommendation.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ItemRecommendationSuccessCode implements BaseSuccessCode {
  RECOMMENDATIONS(HttpStatus.OK, "ITEMREC200_1", "준비물별 추천 상품을 조회했습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
