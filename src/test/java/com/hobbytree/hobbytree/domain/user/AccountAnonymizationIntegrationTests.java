package com.hobbytree.hobbytree.domain.user;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.user.service.AccountAnonymizationScheduler;
import com.hobbytree.hobbytree.domain.user.service.UserService;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

// JUnit5 does not reliably inherit @EnabledIfEnvironmentVariable from IntegrationTestSupport in
// a plain build (see UserIntegrationTests), so it must be repeated here.
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class AccountAnonymizationIntegrationTests extends IntegrationTestSupport {
  @Autowired private AccountAnonymizationScheduler scheduler;
  @Autowired private UserService userService;

  // withdrawn_at is picked so withdrawn_at + 720h lands on the requested side of "now",
  // matching ck_users_withdrawal_lifecycle's fixed restorable_until = withdrawn_at + 720h
  // exactly - it can't be set independently.
  private void withdrawDaysAgo(long userId, int daysAgo) {
    jdbc.update(
        "UPDATE users SET account_status='WITHDRAWN', nickname='탈퇴한너구리',"
            + " age_range='TWENTIES', withdrawn_at=now() - (? * interval '1 day'),"
            + " restorable_until=(now() - (? * interval '1 day')) + interval '720 hours',"
            + " status_before_withdrawal='ACTIVE' WHERE user_id=?",
        daysAgo,
        daysAgo,
        userId);
  }

  private Map<String, Object> row(long userId) {
    return jdbc.queryForMap("SELECT * FROM users WHERE user_id=?", userId);
  }

  @Test
  void sweepAnonymizesOnlyWithdrawnAccountsPastTheRestorableWindow() throws Exception {
    var expired = result(login());
    long expiredId = expired.path("userId").asLong();
    createdUsers.add(expiredId);
    withdrawDaysAgo(expiredId, 31);

    var notYetExpired =
        result(login("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}"));
    long notYetExpiredId = notYetExpired.path("userId").asLong();
    createdUsers.add(notYetExpiredId);
    withdrawDaysAgo(notYetExpiredId, 1);

    scheduler.anonymizeExpiredWithdrawals();

    var anonymized = row(expiredId);
    assertThat(anonymized.get("account_status")).isEqualTo("ANONYMIZED");
    assertThat(anonymized.get("withdrawn_at")).isNull();
    assertThat(anonymized.get("restorable_until")).isNull();
    assertThat(anonymized.get("status_before_withdrawal")).isNull();
    assertThat(anonymized.get("anonymized_at")).isNotNull();
    assertThat(anonymized.get("nickname")).isNull();
    assertThat(anonymized.get("age_range")).isNull();
    assertThat(anonymized.get("provider")).isNull();
    assertThat(anonymized.get("provider_user_id")).isNull();

    var stillWithdrawn = row(notYetExpiredId);
    assertThat(stillWithdrawn.get("account_status")).isEqualTo("WITHDRAWN");
    assertThat(stillWithdrawn.get("restorable_until")).isNotNull();
  }

  @Test
  void anonymizeIfStillWithdrawnSkipsAnAccountRestoredAfterTheScan() throws Exception {
    var signedIn = result(login());
    long userId = signedIn.path("userId").asLong();
    createdUsers.add(userId);
    withdrawDaysAgo(userId, 31);

    // Simulates a restore() landing between the scheduler's own unlocked candidate scan and
    // this per-row transaction - clears exactly the four columns restore() clears.
    jdbc.update(
        "UPDATE users SET account_status='ACTIVE', withdrawn_at=NULL, restorable_until=NULL,"
            + " status_before_withdrawal=NULL WHERE user_id=?",
        userId);

    userService.anonymizeIfStillWithdrawn(userId, Instant.now());

    var current = row(userId);
    assertThat(current.get("account_status")).isEqualTo("ACTIVE");
    assertThat(current.get("nickname")).isEqualTo("탈퇴한너구리");
  }
}
