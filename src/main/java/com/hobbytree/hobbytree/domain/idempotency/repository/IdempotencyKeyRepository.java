package com.hobbytree.hobbytree.domain.idempotency.repository;

import com.hobbytree.hobbytree.domain.idempotency.entity.IdempotencyKey;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
  @Query(
      value =
          "SELECT idempotency_key_id AS id, CAST(response_body AS text) AS context FROM idempotency_keys WHERE idempotency_key_id = :id AND status = 'PENDING' AND ownership_token = :token FOR UPDATE",
      nativeQuery = true)
  Optional<PendingContext> lockPending(@Param("id") Long id, @Param("token") String token);

  // Scalar projection bypasses the persistence-context cache after native CAS updates.
  interface PendingContext {
    Long getId();

    String getContext();
  }

  @Modifying
  @Query(
      value =
          "UPDATE idempotency_keys SET response_body = CAST(:context AS jsonb) WHERE idempotency_key_id = :id AND status = 'PENDING' AND ownership_token = :token",
      nativeQuery = true)
  int savePendingContext(
      @Param("id") Long id, @Param("token") String token, @Param("context") String context);

  @Modifying
  @Query(
      value =
          "UPDATE idempotency_keys SET created_at = :expiredAt WHERE idempotency_key_id = :id AND status = 'PENDING' AND ownership_token = :token",
      nativeQuery = true)
  int expirePending(
      @Param("id") Long id, @Param("token") String token, @Param("expiredAt") Instant expiredAt);

  Optional<IdempotencyKey> findByUserIdAndEndpointAndIdempotencyKey(
      Long userId, String endpoint, String idempotencyKey);

  // ON CONFLICT DO NOTHING instead of catching the unique-violation: a
  // DataIntegrityViolationException caught here would still pass through saveAndFlush's own
  // @Transactional proxy first, marking claim()'s REQUIRES_NEW transaction rollback-only - so
  // the losing concurrent caller's claim() would throw UnexpectedRollbackException (500) at
  // commit instead of returning IN_PROGRESS. A lost race is just an empty result here, nothing
  // to catch.
  @Query(
      value =
          "INSERT INTO idempotency_keys(user_id, endpoint, idempotency_key, request_hash,"
              + " ownership_token) "
              + "VALUES (:userId, :endpoint, :idempotencyKey, :requestHash, :ownershipToken) "
              + "ON CONFLICT (user_id, endpoint, idempotency_key) DO NOTHING "
              + "RETURNING idempotency_key_id",
      nativeQuery = true)
  Optional<Long> insertIfAbsent(
      @Param("userId") Long userId,
      @Param("endpoint") String endpoint,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("requestHash") String requestHash,
      @Param("ownershipToken") String ownershipToken);

  // Atomically takes over an abandoned PENDING row (its original claimer crashed, or a deploy
  // restarted before it could call complete()/release()) - guarded by the same WHERE a
  // concurrent reclaimer would also check, so of two racing reclaim attempts only one ever
  // updates a row; the loser sees 0 rows affected. Minting a new ownership_token here is what
  // lets a later complete()/release() from the ORIGINAL (no longer owning) caller tell that
  // it lost the claim, instead of acting on a row someone else now owns.
  @Modifying
  @Query(
      value =
          "UPDATE idempotency_keys SET request_hash = :requestHash, created_at = now(),"
              + " ownership_token = :ownershipToken "
              + "WHERE idempotency_key_id = :id AND status = 'PENDING' AND created_at < :expiryThreshold",
      nativeQuery = true)
  int reclaimExpired(
      @Param("id") Long id,
      @Param("requestHash") String requestHash,
      @Param("expiryThreshold") Instant expiryThreshold,
      @Param("ownershipToken") String ownershipToken);

  // CAS: only the caller that still holds this exact token may complete the claim. 0 rows
  // means ownership moved on (reclaimed by a retry) - the caller must not treat its own work
  // as the recorded outcome for this key.
  @Modifying
  @Query(
      value =
          "UPDATE idempotency_keys SET status = 'COMPLETED', response_status = :responseStatus,"
              + " response_body = CAST(:responseBody AS jsonb) "
              + "WHERE idempotency_key_id = :id AND ownership_token = :ownershipToken",
      nativeQuery = true)
  int completeIfOwned(
      @Param("id") Long id,
      @Param("ownershipToken") String ownershipToken,
      @Param("responseStatus") int responseStatus,
      @Param("responseBody") String responseBody);

  // CAS: deletes only while still owned. If ownership already moved on, there is nothing of
  // this caller's left to clean up - leave whatever the current owner has written alone.
  @Modifying
  @Query(
      value =
          "DELETE FROM idempotency_keys WHERE idempotency_key_id = :id AND ownership_token ="
              + " :ownershipToken",
      nativeQuery = true)
  int releaseIfOwned(@Param("id") Long id, @Param("ownershipToken") String ownershipToken);
}
