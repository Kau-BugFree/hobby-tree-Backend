package com.hobbytree.hobbytree.domain.sponsoredcontent.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum SponsoredContentSuccessCode implements BaseSuccessCode {
  FOUND("SPONSORED200_1", "활동 관련 제휴 콘텐츠를 조회했습니다.");
  private final HttpStatus status = HttpStatus.OK;
  private final String code;
  private final String message;
}
