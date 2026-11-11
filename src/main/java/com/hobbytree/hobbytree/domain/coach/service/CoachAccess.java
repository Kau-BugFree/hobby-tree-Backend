package com.hobbytree.hobbytree.domain.coach.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.user.entity.User;

final class CoachAccess {
  private CoachAccess() {}

  static User requireActive(User user) {
    switch (user.getAccountStatus()) {
      case ACTIVE -> {
        return user;
      }
      case PENDING_ONBOARDING -> throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);
      case SUSPENDED -> throw new AuthException(AuthErrorCode.SUSPENDED);
      case WITHDRAWN -> throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
      default -> throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    }
  }
}
