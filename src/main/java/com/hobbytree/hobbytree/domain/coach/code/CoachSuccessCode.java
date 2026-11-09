package com.hobbytree.hobbytree.domain.coach.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum CoachSuccessCode implements BaseSuccessCode {
  CHAT(HttpStatus.OK, "COACH200_1", "하비 코치 답변이 생성되었습니다."),
  CONVERSATIONS(HttpStatus.OK, "COACH200_2", "대화 목록을 조회했습니다."),
  MESSAGES(HttpStatus.OK, "COACH200_3", "대화 메시지를 조회했습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;
}
