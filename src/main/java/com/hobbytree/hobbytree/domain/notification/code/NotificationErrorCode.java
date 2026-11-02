package com.hobbytree.hobbytree.domain.notification.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum NotificationErrorCode implements BaseErrorCode {
  NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION404_1", "본인 소유의 알림을 찾을 수 없음");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
