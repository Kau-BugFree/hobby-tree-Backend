package com.hobbytree.hobbytree.domain.region.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum RegionSuccessCode implements BaseSuccessCode {
  SEARCH("LOCATION200_1", "지역 검색 결과를 조회했습니다.");
  private final HttpStatus status = HttpStatus.OK;
  private final String code;
  private final String message;
}
