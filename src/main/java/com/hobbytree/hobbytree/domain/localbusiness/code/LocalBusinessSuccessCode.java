package com.hobbytree.hobbytree.domain.localbusiness.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum LocalBusinessSuccessCode implements BaseSuccessCode {
  SEARCH(HttpStatus.OK, "LOCALBUSINESS200_1", "지역 업체 검색 결과를 조회했습니다."),
  SELECT(HttpStatus.OK, "LOCALBUSINESS200_2", "선택한 업체를 예약 가능한 상태로 준비했습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
