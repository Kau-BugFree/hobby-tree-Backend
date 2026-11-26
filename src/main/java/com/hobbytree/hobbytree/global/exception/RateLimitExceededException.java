package com.hobbytree.hobbytree.global.exception;

import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import lombok.Getter;

@Getter
public class RateLimitExceededException extends ProjectException {
  private final long retryAfterSeconds;

  public RateLimitExceededException(long retryAfterSeconds) {
    super(GeneralErrorCode.TOO_MANY_REQUESTS);
    this.retryAfterSeconds = retryAfterSeconds;
  }
}
