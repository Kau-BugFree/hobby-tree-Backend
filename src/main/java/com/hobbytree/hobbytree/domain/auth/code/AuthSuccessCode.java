package com.hobbytree.hobbytree.domain.auth.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum AuthSuccessCode implements BaseSuccessCode {
  SOCIAL_LOGIN("AUTH200_1", "소셜 로그인에 성공했습니다."),
  REISSUE("AUTH200_2", "토큰이 재발급되었습니다."),
  LOGOUT("AUTH200_3", "로그아웃되었습니다.");
  private final HttpStatus status = HttpStatus.OK;
  private final String code;
  private final String message;
}
