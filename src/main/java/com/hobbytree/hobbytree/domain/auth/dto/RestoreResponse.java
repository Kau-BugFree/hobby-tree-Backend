package com.hobbytree.hobbytree.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;

public record RestoreResponse(
    Long userId,
    String accessToken,
    String refreshToken,
    String tokenType,
    long accessTokenExpiresIn,
    long refreshTokenExpiresIn,
    AccountStatus memberStatus,
    @JsonProperty("isNewUser") boolean isNewUser,
    boolean restored) {
  @Override
  public String toString() {
    return "RestoreResponse[userId=" + userId + ", tokens=[redacted]]";
  }
}
