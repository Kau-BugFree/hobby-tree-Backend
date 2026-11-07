package com.hobbytree.hobbytree.domain.tree.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum TreeSuccessCode implements BaseSuccessCode {
  LIST(HttpStatus.OK, "TREE200_1", "내 취미 나무 목록을 조회했습니다."),
  DETAIL(HttpStatus.OK, "TREE200_2", "취미 나무 상세와 성장 현황을 조회했습니다."),
  CREATE(HttpStatus.CREATED, "TREE201_1", "취미 나무가 심어졌습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
