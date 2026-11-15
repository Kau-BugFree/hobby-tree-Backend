package com.hobbytree.hobbytree.domain.location.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum LocationSuccessCode implements BaseSuccessCode {
  SET(HttpStatus.OK, "LOCATION200_2", "활동 지역이 설정되었습니다."),
  CONSENT_RECORDED(HttpStatus.CREATED, "LOCATION201_1", "위치 이용 동의 상태가 기록되었습니다.");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
