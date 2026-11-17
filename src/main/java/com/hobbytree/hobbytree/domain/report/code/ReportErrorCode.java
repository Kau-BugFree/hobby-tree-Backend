package com.hobbytree.hobbytree.domain.report.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ReportErrorCode implements BaseErrorCode {
  UNSUPPORTED_REQUEST(HttpStatus.BAD_REQUEST, "REPORT400_1", "지원하지 않는 대상·사유 또는 설명 길이 오류"),
  TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "REPORT404_1", "신고할 수 있는 대상을 찾을 수 없음"),
  DUPLICATE(HttpStatus.CONFLICT, "REPORT409_1", "같은 대상·사유에 대한 24시간 내 중복 신고");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
