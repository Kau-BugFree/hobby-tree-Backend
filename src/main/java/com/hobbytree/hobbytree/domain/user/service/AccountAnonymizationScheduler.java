package com.hobbytree.hobbytree.domain.user.service;

import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Issue #276: restorable_until only ever lands 30 days after withdrawal
// (ck_users_withdrawal_lifecycle fixes it to withdrawn_at + 720h), so a daily sweep gives more
// than enough lead time - there's no benefit to running this more often.
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountAnonymizationScheduler {
  private final UserRepository users;
  private final UserService userService;
  private final Clock clock;

  @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
  public void anonymizeExpiredWithdrawals() {
    Instant now = clock.instant();
    // Unlocked scan for candidates; anonymizeIfStillWithdrawn re-checks and locks each one
    // individually, in its own transaction, so one bad row can't roll back the rest of the
    // batch and a concurrent restore() in the gap since this scan is never overwritten.
    List<Long> candidates =
        users.findIdsByAccountStatusAndRestorableUntilAtMost(AccountStatus.WITHDRAWN, now);
    for (Long userId : candidates) {
      try {
        userService.anonymizeIfStillWithdrawn(userId, now);
      } catch (RuntimeException e) {
        log.error("Failed to anonymize withdrawn user: userId={}", userId, e);
      }
    }
  }
}
