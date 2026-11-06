package com.hobbytree.hobbytree.domain.hobby.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum HobbyErrorCode implements BaseErrorCode {
  UNSUPPORTED_FILTER(HttpStatus.BAD_REQUEST, "HOBBY400_1", "지원하지 않는 필터·정렬 또는 잘못된 커서");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
