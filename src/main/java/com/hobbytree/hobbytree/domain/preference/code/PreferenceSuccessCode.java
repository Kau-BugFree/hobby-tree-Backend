package com.hobbytree.hobbytree.domain.preference.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum PreferenceSuccessCode implements BaseSuccessCode {
  SAVED("PREFERENCE200_1", "취향 설정이 저장되었습니다.");
  private final HttpStatus status = HttpStatus.OK;
  private final String code;
  private final String message;
}
