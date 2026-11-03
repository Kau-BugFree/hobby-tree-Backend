package com.hobbytree.hobbytree.domain.activity.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum SavedActivitySuccessCode implements BaseSuccessCode {
  SAVED("SAVED200_1", "활동이 저장되었습니다.");
  private final HttpStatus status = HttpStatus.OK;
  private final String code;
  private final String message;
}
