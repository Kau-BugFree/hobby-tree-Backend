package com.hobbytree.hobbytree.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;

public record SocialLoginResponse(
    Long userId,
    String accessToken,
    String refreshToken,
    String tokenType,
    long accessTokenExpiresIn,
    long refreshTokenExpiresIn,
    AccountStatus memberStatus,
    @JsonProperty("isNewUser") boolean isNewUser) {
  @Override
  public String toString() {
    return "SocialLoginResponse[userId=" + userId + ", tokens=[redacted]]";
  }
}
