package com.hobbytree.hobbytree.domain.location.code;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum LocationErrorCode implements BaseErrorCode {
  INVALID_SOURCE_FIELDS(HttpStatus.BAD_REQUEST, "LOCATION400_2", "입력 방식별 필수값 누락·좌표 범위 오류·필드 혼합"),
  CONSENT_MISSING(HttpStatus.FORBIDDEN, "LOCATION403_1", "기기 위치 이용 동의가 없거나 철회됨"),
  REGION_NOT_FOUND(HttpStatus.NOT_FOUND, "LOCATION404_1", "설정 가능한 지역을 찾을 수 없음"),
  // Distinct endpoint from API-43's own LOCATION409_1 ("동의 안내문 버전이 변경됨") - the code
  // string is reused across endpoints in this project (see AuthErrorCode's AUTH401_3), the
  // enum constant name is what disambiguates.
  CONSENT_VERSION_MISMATCH(HttpStatus.CONFLICT, "LOCATION409_1", "위치 동의 버전 불일치"),
  CONVERSION_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "LOCATION503_1", "좌표를 지역으로 변환하는 서비스를 사용할 수 없음");
  private final HttpStatus status;
  private final String code;
  private final String message;
}
