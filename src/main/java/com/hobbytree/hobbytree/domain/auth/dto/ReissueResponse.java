package com.hobbytree.hobbytree.domain.auth.dto;

import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;

public record ReissueResponse(
    Long userId,
    String accessToken,
    String refreshToken,
    String tokenType,
    long accessTokenExpiresIn,
    long refreshTokenExpiresIn,
    AccountStatus memberStatus) {
  @Override
  public String toString() {
    return "ReissueResponse[userId=" + userId + ", tokens=[redacted]]";
  }
}
