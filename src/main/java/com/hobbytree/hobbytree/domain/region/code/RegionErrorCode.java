package com.hobbytree.hobbytree.domain.region.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum RegionErrorCode implements BaseErrorCode {
  INVALID_SEARCH(HttpStatus.BAD_REQUEST, "LOCATION400_1", "검색어·지역 단계·커서가 올바르지 않음");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
