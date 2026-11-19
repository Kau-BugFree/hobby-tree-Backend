package com.hobbytree.hobbytree.domain.activity.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ActivityRecordErrorCode implements BaseErrorCode {
  INVALID_PROGRESS(HttpStatus.BAD_REQUEST, "RECORD400_2", "다른 활동의 단계 ID·중복 ID·잘못된 시간"),
  ALREADY_FINISHED(HttpStatus.CONFLICT, "RECORD409_2", "이미 종료된 기록"),
  VERSION_CONFLICT(HttpStatus.CONFLICT, "RECORD409_3", "수정 버전 불일치"),
  NOT_FOUND(HttpStatus.NOT_FOUND, "RECORD404_1", "본인 소유의 활동 기록을 찾을 수 없음"),
  HOBBY_MISMATCH(HttpStatus.BAD_REQUEST, "RECORD400_1", "활동이 나무의 취미에 속하지 않음"),
  STAGE_LOCKED(HttpStatus.FORBIDDEN, "ACTIVITY403_1", "아직 열리지 않은 성장 단계의 활동"),
  NOT_STARTABLE(HttpStatus.NOT_FOUND, "ACTIVITY404_1", "활동이 없거나 시작할 수 없음"),
  OTHER_IN_PROGRESS(HttpStatus.CONFLICT, "RECORD409_1", "같은 나무에서 다른 활동을 진행 중"),
  LEGACY_SNAPSHOT_REQUIRED(HttpStatus.CONFLICT, "RECORD409_5", "과거 진행 기록의 시작 스냅샷 정비가 필요합니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
