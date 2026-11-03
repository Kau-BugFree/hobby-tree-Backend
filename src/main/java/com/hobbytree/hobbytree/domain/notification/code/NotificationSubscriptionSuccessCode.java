package com.hobbytree.hobbytree.domain.notification.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

// Unlike NotificationSuccessCode, status varies per entry (201 on first subscribe, 200 on
// repeat), so it is a real constructor parameter instead of a fixed field initializer.
@Getter
@AllArgsConstructor
public enum NotificationSubscriptionSuccessCode implements BaseSuccessCode {
  CHAT_LAUNCH_SUBSCRIBED(HttpStatus.CREATED, "SUBSCRIPTION201_2", "채팅 출시 알림이 신청되었습니다."),
  CHAT_LAUNCH_ALREADY_SUBSCRIBED(HttpStatus.OK, "SUBSCRIPTION200_2", "이미 채팅 출시 알림을 신청했습니다.");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
