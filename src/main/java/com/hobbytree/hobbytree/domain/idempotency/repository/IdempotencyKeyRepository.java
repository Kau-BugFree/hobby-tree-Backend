package com.hobbytree.hobbytree.domain.idempotency.repository;

import com.hobbytree.hobbytree.domain.idempotency.entity.IdempotencyKey;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
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
          "INSERT INTO idempotency_keys(user_id, endpoint, idempotency_key, request_hash) "
              + "VALUES (:userId, :endpoint, :idempotencyKey, :requestHash) "
              + "ON CONFLICT (user_id, endpoint, idempotency_key) DO NOTHING "
              + "RETURNING idempotency_key_id",
      nativeQuery = true)
  Optional<Long> insertIfAbsent(
      @Param("userId") Long userId,
      @Param("endpoint") String endpoint,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("requestHash") String requestHash);

  // Atomically takes over an abandoned PENDING row (its original claimer crashed, or a deploy
  // restarted before it could call complete()/release()) - guarded by the same WHERE a
  // concurrent reclaimer would also check, so of two racing reclaim attempts only one ever
  // updates a row; the loser sees 0 rows affected.
  @Modifying
  @Query(
      value =
          "UPDATE idempotency_keys SET request_hash = :requestHash, created_at = now() "
              + "WHERE idempotency_key_id = :id AND status = 'PENDING' AND created_at < :expiryThreshold",
      nativeQuery = true)
  int reclaimExpired(
      @Param("id") Long id,
      @Param("requestHash") String requestHash,
      @Param("expiryThreshold") Instant expiryThreshold);
}
