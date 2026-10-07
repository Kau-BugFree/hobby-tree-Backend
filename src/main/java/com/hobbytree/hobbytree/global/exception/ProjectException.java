package com.hobbytree.hobbytree.global.exception;

import com.hobbytree.hobbytree.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;

@Getter
public class ProjectException extends RuntimeException {

  private final BaseErrorCode errorCode;

  public ProjectException(BaseErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }
}
