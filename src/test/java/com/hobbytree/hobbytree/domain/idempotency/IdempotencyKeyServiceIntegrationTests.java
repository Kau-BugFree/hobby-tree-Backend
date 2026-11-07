package com.hobbytree.hobbytree.domain.idempotency;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyClaim;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyService;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class IdempotencyKeyServiceIntegrationTests extends IntegrationTestSupport {
  private static final String ENDPOINT = "POST /api/v1/recommendations";

  @Autowired private IdempotencyKeyService idempotencyKeys;

  @Test
  void concurrentClaimsWithTheSameKeyAndBodyNeverThrowAndOnlyOneIsClaimed() throws Exception {
    long userId = otherUser();
    String key = UUID.randomUUID().toString();
    String hash = "same-hash";

    var results = raceClaims(() -> idempotencyKeys.claim(userId, ENDPOINT, key, hash), 2);

    assertThat(results)
        .extracting(IdempotencyKeyClaim::outcome)
        .containsExactlyInAnyOrder(
            IdempotencyKeyClaim.Outcome.CLAIMED, IdempotencyKeyClaim.Outcome.IN_PROGRESS);
  }

  @Test
  void concurrentClaimsWithTheSameKeyButDifferentBodiesNeverThrowAndOneConflicts()
      throws Exception {
    long userId = otherUser();
    String key = UUID.randomUUID().toString();

    var results =
        raceClaims(
            List.of(
                () -> idempotencyKeys.claim(userId, ENDPOINT, key, "hash-a"),
                () -> idempotencyKeys.claim(userId, ENDPOINT, key, "hash-b")));

    assertThat(results)
        .extracting(IdempotencyKeyClaim::outcome)
        .containsExactlyInAnyOrder(
            IdempotencyKeyClaim.Outcome.CLAIMED, IdempotencyKeyClaim.Outcome.CONFLICT);
  }

  @Test
  void rejectsAKeyLongerThanTheColumnWithoutTouchingTheDatabase() {
    long userId = otherUser();
    String tooLong = "x".repeat(101);

    assertThatThrownBy(() -> idempotencyKeys.claim(userId, ENDPOINT, tooLong, "hash"))
        .isInstanceOf(ProjectException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE user_id=?", Integer.class, userId))
        .isZero();
  }

  @Test
  void rejectsABlankKey() {
    long userId = otherUser();
    assertThatThrownBy(() -> idempotencyKeys.claim(userId, ENDPOINT, " ", "hash"))
        .isInstanceOf(ProjectException.class);
  }

  @Test
  void reclaimsAnExpiredPendingRowInsteadOfReturningInProgressForever() {
    long userId = otherUser();
    String key = UUID.randomUUID().toString();
    // Same hash as the abandoned attempt: this is the realistic case (the client resends the
    // identical request because it never got a response the first time), not a key reused for
    // a genuinely different body - that case must still be CONFLICT regardless of staleness.
    seedPending(userId, key, "same-hash", Instant.now().minus(10, ChronoUnit.MINUTES));

    var claim = idempotencyKeys.claim(userId, ENDPOINT, key, "same-hash");

    assertThat(claim.outcome()).isEqualTo(IdempotencyKeyClaim.Outcome.CLAIMED);
    // created_at moved forward off its 10-minutes-stale seed value, proving the row was
    // actually reclaimed rather than just happening to still satisfy the lease window.
    assertThat(
            jdbc.queryForObject(
                    "SELECT created_at FROM idempotency_keys WHERE user_id=? AND idempotency_key=?",
                    java.sql.Timestamp.class,
                    userId,
                    key)
                .toInstant())
        .isAfter(Instant.now().minus(1, ChronoUnit.MINUTES));
  }

  @Test
  void doesNotReclaimAPendingRowStillWithinItsLease() {
    long userId = otherUser();
    String key = UUID.randomUUID().toString();
    seedPending(userId, key, "same-hash", Instant.now().minus(1, ChronoUnit.MINUTES));

    var claim = idempotencyKeys.claim(userId, ENDPOINT, key, "same-hash");

    assertThat(claim.outcome()).isEqualTo(IdempotencyKeyClaim.Outcome.IN_PROGRESS);
  }

  @Test
  void replaysACompletedRowWithTheSameHash() {
    long userId = otherUser();
    String key = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO idempotency_keys(user_id,endpoint,idempotency_key,request_hash,status,"
            + "response_status,response_body) VALUES (?,?,?,?,'COMPLETED',201,'{\"ok\":true}'::jsonb)",
        userId,
        ENDPOINT,
        key,
        "matching-hash");

    var claim = idempotencyKeys.claim(userId, ENDPOINT, key, "matching-hash");

    assertThat(claim.outcome()).isEqualTo(IdempotencyKeyClaim.Outcome.REPLAY);
    assertThat(claim.responseStatus()).isEqualTo(201);
    // jsonb round-trips with Postgres's own normalized spacing, not the literal we inserted.
    assertThat(claim.responseBody()).isEqualTo("{\"ok\": true}");
  }

  private void seedPending(long userId, String key, String hash, Instant createdAt) {
    jdbc.update(
        "INSERT INTO idempotency_keys(user_id,endpoint,idempotency_key,request_hash,status,"
            + "created_at) VALUES (?,?,?,?,'PENDING',?)",
        userId,
        ENDPOINT,
        key,
        hash,
        java.sql.Timestamp.from(createdAt));
  }

  private long otherUser() {
    long id =
        jdbc.queryForObject(
            "INSERT INTO users(provider,provider_user_id,account_status) VALUES"
                + " ('KAKAO',?,'ACTIVE') RETURNING user_id",
            Long.class,
            "idempotency-test-" + UUID.randomUUID());
    createdUsers.add(id);
    return id;
  }

  private List<IdempotencyKeyClaim> raceClaims(Callable<IdempotencyKeyClaim> call, int participants)
      throws Exception {
    return raceClaims(List.copyOf(java.util.Collections.nCopies(participants, call)));
  }

  private List<IdempotencyKeyClaim> raceClaims(List<Callable<IdempotencyKeyClaim>> calls)
      throws Exception {
    var barrier = new CyclicBarrier(calls.size());
    try (var executor = Executors.newFixedThreadPool(calls.size())) {
      List<Future<IdempotencyKeyClaim>> futures =
          calls.stream()
              .map(
                  call ->
                      executor.submit(
                          () -> {
                            barrier.await();
                            return call.call();
                          }))
              .toList();
      List<IdempotencyKeyClaim> results = new java.util.ArrayList<>();
      for (var future : futures) results.add(future.get(15, java.util.concurrent.TimeUnit.SECONDS));
      return results;
    }
  }
}
