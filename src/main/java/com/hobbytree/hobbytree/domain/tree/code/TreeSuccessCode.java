package com.hobbytree.hobbytree.domain.tree.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum TreeSuccessCode implements BaseSuccessCode {
  LIST("TREE200_1", "내 취미 나무 목록을 조회했습니다.");

  private final HttpStatus status = HttpStatus.OK;
  private final String code;
  private final String message;
}
