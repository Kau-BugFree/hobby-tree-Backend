package com.hobbytree.hobbytree.domain.user;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.domain.user.service.AccountAnonymizationScheduler;
import com.hobbytree.hobbytree.domain.user.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountAnonymizationSchedulerTests {
  @Test
  void aFailingCandidateDoesNotStopTheRestOfTheBatch() {
    var users = mock(UserRepository.class);
    var userService = mock(UserService.class);
    var clock = Clock.fixed(Instant.parse("2026-01-01T04:00:00Z"), java.time.ZoneOffset.UTC);
    when(users.findIdsByAccountStatusAndRestorableUntilAtMost(eq(AccountStatus.WITHDRAWN), any()))
        .thenReturn(List.of(1L, 2L, 3L));
    doThrow(new RuntimeException("boom"))
        .when(userService)
        .anonymizeIfStillWithdrawn(eq(2L), any());

    var scheduler = new AccountAnonymizationScheduler(users, userService, clock);
    scheduler.anonymizeExpiredWithdrawals();

    verify(userService).anonymizeIfStillWithdrawn(1L, clock.instant());
    verify(userService).anonymizeIfStillWithdrawn(2L, clock.instant());
    verify(userService).anonymizeIfStillWithdrawn(3L, clock.instant());
  }
}
