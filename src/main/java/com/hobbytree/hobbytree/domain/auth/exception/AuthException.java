package com.hobbytree.hobbytree.domain.auth.exception;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import lombok.Getter;

@Getter
public class AuthException extends ProjectException {
  private final Object result;
  private final Long retryAfter;

  public AuthException(AuthErrorCode code) {
    this(code, null, null);
  }

  public AuthException(AuthErrorCode code, Object result, Long retryAfter) {
    super(code);
    this.result = result;
    this.retryAfter = retryAfter;
  }
}
