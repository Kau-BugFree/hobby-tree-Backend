package com.hobbytree.hobbytree.domain.user.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum UserSuccessCode implements BaseSuccessCode {
  PROFILE_VIEW("USER200_1", "내 프로필을 조회했습니다."),
  WITHDRAW("USER200_3", "회원 탈퇴가 처리되었습니다.");
  private final HttpStatus status = HttpStatus.OK;
  private final String code;
  private final String message;
}
