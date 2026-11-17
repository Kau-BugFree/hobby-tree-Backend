package com.hobbytree.hobbytree.domain.preference.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum PreferenceErrorCode implements BaseErrorCode {
  INVALID_SELECTION(HttpStatus.BAD_REQUEST, "PREFERENCE400_1", "선택값·필수 필드·카테고리 개수가 올바르지 않음"),
  NOT_FOUND(HttpStatus.NOT_FOUND, "PREFERENCE404_1", "아직 저장된 취향 설정이 없음"),
  OPTIONS_VERSION_CHANGED(HttpStatus.CONFLICT, "PREFERENCE409_1", "선택지 버전이 변경됨. API 10 재조회 필요"),
  VERSION_CONFLICT(HttpStatus.CONFLICT, "PREFERENCE409_2", "expectedVersion 불일치. API 12 재조회 필요");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
