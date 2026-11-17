package com.hobbytree.hobbytree.domain.report.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ReportSuccessCode implements BaseSuccessCode {
  RECEIVED("REPORT201_1", "신고가 접수되었습니다.");
  private final HttpStatus status = HttpStatus.CREATED;
  private final String code;
  private final String message;
}
