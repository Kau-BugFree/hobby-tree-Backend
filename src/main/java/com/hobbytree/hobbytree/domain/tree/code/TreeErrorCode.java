package com.hobbytree.hobbytree.domain.tree.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum TreeErrorCode implements BaseErrorCode {
  NOT_FOUND(HttpStatus.NOT_FOUND, "TREE404_1", "본인 소유의 취미 나무를 찾을 수 없음"),
  ALREADY_PLANTED(HttpStatus.CONFLICT, "TREE409_1", "같은 취미의 나무를 이미 보유함. 기존 나무 ID 확인"),
  NO_AVAILABLE_SLOT(HttpStatus.CONFLICT, "TREE409_2", "최대 5그루의 슬롯을 모두 사용 중");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
