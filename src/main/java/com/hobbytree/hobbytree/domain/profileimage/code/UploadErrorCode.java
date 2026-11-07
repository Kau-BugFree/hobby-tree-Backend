package com.hobbytree.hobbytree.domain.profileimage.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum UploadErrorCode implements BaseErrorCode {
  OBJECT_MISMATCH(HttpStatus.BAD_REQUEST, "UPLOAD400_1", "업로드 객체 누락·내용 형식 불일치·용량 초과"),
  UNSUPPORTED_FILE(HttpStatus.BAD_REQUEST, "UPLOAD400_2", "허용되지 않는 파일 형식·파일명"),
  NOT_OWNED(HttpStatus.FORBIDDEN, "UPLOAD403_1", "본인에게 발급되지 않은 업로드 키"),
  FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD413_1", "신고한 파일 크기가 5 MiB 초과"),
  ISSUANCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD503_1", "업로드 URL 발급을 일시적으로 처리할 수 없음");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
