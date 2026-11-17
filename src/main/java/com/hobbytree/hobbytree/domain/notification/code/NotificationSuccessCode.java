package com.hobbytree.hobbytree.domain.notification.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum NotificationSuccessCode implements BaseSuccessCode {
  LIST("NOTIFICATION200_1", "알림 목록을 조회했습니다."),
  READ("NOTIFICATION200_2", "알림이 읽음 처리되었습니다."),
  SETTINGS_UPDATE("NOTIFICATION200_3", "알림 설정이 변경되었습니다.");
  private final HttpStatus status = HttpStatus.OK;
  private final String code;
  private final String message;
}
