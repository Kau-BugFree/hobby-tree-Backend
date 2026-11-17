package com.hobbytree.hobbytree.domain.notification.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum NotificationErrorCode implements BaseErrorCode {
  NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION404_1", "본인 소유의 알림을 찾을 수 없음"),
  CHAT_LAUNCH_CLOSED(HttpStatus.CONFLICT, "SUBSCRIPTION409_2", "이미 출시되었거나 신청 기간이 종료됨"),
  CLASS_NOT_OPEN_ELIGIBLE(HttpStatus.CONFLICT, "SUBSCRIPTION409_1", "이미 오픈했거나 오픈 알림 신청 대상이 아님");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
