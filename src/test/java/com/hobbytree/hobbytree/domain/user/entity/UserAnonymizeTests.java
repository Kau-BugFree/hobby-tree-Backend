package com.hobbytree.hobbytree.domain.user.entity;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

// Issue #276: anonymize() must clear every column ck_users_withdrawal_lifecycle and
// ck_users_social_identity (V3__enforce_user_withdrawal_backfill.sql) require to be
// null/non-null together for ANONYMIZED - a plain unit test since no DB round trip is needed to
// check this.
class UserAnonymizeTests {
  @Test
  void clearsEveryWithdrawalAndPiiColumnAndFreesTheSocialIdentity() {
    User user = User.socialMember(AuthProvider.KAKAO, "social-id-123");
    user.updateProfile("탈퇴한너구리", AgeRange.TWENTIES);
    user.linkProfileImage("profiles/1/final/old.jpg");
    user.withdraw(Instant.parse("2026-01-01T00:00:00Z"));

    Instant anonymizedAt = Instant.parse("2026-02-01T00:00:00Z");
    user.anonymize(anonymizedAt);

    assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.ANONYMIZED);
    assertThat(user.getAnonymizedAt()).isEqualTo(anonymizedAt);
    assertThat(user.getWithdrawnAt()).isNull();
    assertThat(user.getRestorableUntil()).isNull();
    assertThat(user.getStatusBeforeWithdrawal()).isNull();
    assertThat(user.getNickname()).isNull();
    assertThat(user.getProfileImageKey()).isNull();
    assertThat(user.getAgeRange()).isNull();
    assertThat(user.getProvider()).isNull();
    assertThat(user.getProviderUserId()).isNull();
  }
}
