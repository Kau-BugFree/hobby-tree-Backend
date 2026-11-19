package com.hobbytree.hobbytree.domain.activity.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ActivityRecordSuccessCode implements BaseSuccessCode {
  STARTED(HttpStatus.CREATED, "RECORD201_1", "활동을 시작했습니다."),
  REUSED(HttpStatus.OK, "RECORD200_1", "진행 중인 활동을 조회했습니다."),
  DETAIL(HttpStatus.OK, "RECORD200_2", "활동 진행 상황을 조회했습니다."),
  PROGRESS_SAVED(HttpStatus.OK, "RECORD200_3", "활동 진행 상황이 저장되었습니다."),
  COMPLETED(HttpStatus.OK, "RECORD200_4", "활동이 완료 처리되었습니다."),
  LIST(HttpStatus.OK, "RECORD200_5", "취미 나무별 활동 기록을 조회했습니다."),
  DELETED(HttpStatus.OK, "RECORD200_6", "활동 기록을 삭제했습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
