package com.hobbytree.hobbytree.domain.user.service;

import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.user.code.UserErrorCode;
import com.hobbytree.hobbytree.domain.user.dto.WithdrawResponse;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final UserRepository users;
  private final RedisAuthStore store;
  private final Clock clock;

  public UserService(UserRepository users, RedisAuthStore store, Clock clock) {
    this.users = users;
    this.store = store;
    this.clock = clock;
  }

  @Transactional
  public WithdrawResponse withdraw(Long userId) {
    // AuthFilter already checked status on an unlocked read; re-check under lock in case it
    // changed (e.g. a concurrent withdraw/suspend) between that check and this transaction.
    User user =
        users
            .findForLogin(userId)
            .orElseThrow(() -> new ProjectException(UserErrorCode.WITHDRAWAL_CONFLICT));
    if (user.getAccountStatus() != AccountStatus.ACTIVE
        && user.getAccountStatus() != AccountStatus.PENDING_ONBOARDING)
      throw new ProjectException(UserErrorCode.WITHDRAWAL_CONFLICT);

    user.withdraw(clock.instant());
    store.revokeOnWithdrawal(userId);
    return new WithdrawResponse(
        user.getUserId(),
        user.getAccountStatus(),
        user.getWithdrawnAt(),
        user.getRestorableUntil());
  }
}
