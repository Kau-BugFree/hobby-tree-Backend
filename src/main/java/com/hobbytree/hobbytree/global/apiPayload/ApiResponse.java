package com.hobbytree.hobbytree.global.apiPayload;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import com.hobbytree.hobbytree.global.apiPayload.code.BaseSuccessCode;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ApiResponse<T> {

  @Getter(AccessLevel.NONE)
  private final boolean isSuccess;

  private final String code;
  private final String message;
  private final T result;

  @JsonProperty("isSuccess")
  public boolean isSuccess() {
    return isSuccess;
  }

  // 성공
  public static <T> ApiResponse<T> onSuccess(BaseSuccessCode code, T result) {
    return new ApiResponse<>(true, code.getCode(), code.getMessage(), result);
  }

  // 실패
  public static <T> ApiResponse<T> onFailure(BaseErrorCode code, T result) {
    return new ApiResponse<>(false, code.getCode(), code.getMessage(), result);
  }
}
