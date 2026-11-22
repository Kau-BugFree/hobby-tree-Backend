package com.hobbytree.hobbytree.domain.localbusiness.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum LocalBusinessErrorCode implements BaseErrorCode {
  UPSTREAM_BAD_RESPONSE(HttpStatus.BAD_GATEWAY, "LOCALBUSINESS502_1", "지역 검색 서비스 응답을 확인할 수 없음"),
  UPSTREAM_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "LOCALBUSINESS503_1", "지역 검색 서비스를 일시적으로 사용할 수 없음"),
  // Catalog gap, not an upstream failure: the requested hobby category has no seeded Hobby row.
  HOBBY_NOT_CONFIGURED(
      HttpStatus.SERVICE_UNAVAILABLE, "LOCALBUSINESS503_2", "선택한 취미 카테고리가 아직 설정되지 않음");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
