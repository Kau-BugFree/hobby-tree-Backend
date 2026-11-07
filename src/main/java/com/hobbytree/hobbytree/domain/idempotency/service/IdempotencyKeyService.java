package com.hobbytree.hobbytree.domain.idempotency.service;

import com.hobbytree.hobbytree.domain.idempotency.entity.IdempotencyKey;
import com.hobbytree.hobbytree.domain.idempotency.entity.IdempotencyKeyStatus;
import com.hobbytree.hobbytree.domain.idempotency.repository.IdempotencyKeyRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Generic idempotency support, reusable beyond API-14 (the first caller). A caller's own
// write still needs its own transaction, separate from claim()'s: claim() always commits
// immediately (REQUIRES_NEW), even when called from inside an already-open transaction, so a
// concurrent duplicate request can see this row as PENDING while this caller's (possibly
// slow) work is still in flight - a transaction that only committed at the very end would
// make that race window invisible to everyone until it's already too late to matter.
@Service
@RequiredArgsConstructor
public class IdempotencyKeyService {
  // Matches idempotency_keys.idempotency_key's VARCHAR(100); an oversized header would
  // otherwise fail the insert below and (before this check existed) surface as either a
  // disguised IN_PROGRESS or a 500, never the 400 an invalid request actually deserves.
  private static final int MAX_KEY_LENGTH = 100;
  // A claimer that never reaches complete()/release() - a crash, a deploy restart, or a
  // commit failure after create() already returned to the caller - would otherwise leave this
  // row PENDING forever, permanently returning IN_PROGRESS to every retry with the same key.
  // An expired PENDING row is instead reclaimable by a later attempt.
  private static final Duration PENDING_LEASE = Duration.ofMinutes(5);

  private final IdempotencyKeyRepository keys;
  private final Clock clock;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IdempotencyKeyClaim claim(
      Long userId, String endpoint, String idempotencyKey, String requestHash) {
    if (idempotencyKey == null
        || idempotencyKey.isBlank()
        || idempotencyKey.length() > MAX_KEY_LENGTH)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);

    var inserted = keys.insertIfAbsent(userId, endpoint, idempotencyKey, requestHash);
    if (inserted.isPresent()) return IdempotencyKeyClaim.claimed(inserted.get());

    // Lost the race to a concurrent claim, or a row from an earlier attempt already exists -
    // insertIfAbsent never throws on conflict, so there is nothing here to roll back.
    IdempotencyKey existing =
        keys.findByUserIdAndEndpointAndIdempotencyKey(userId, endpoint, idempotencyKey)
            .orElseThrow(
                () -> new IllegalStateException("idempotency key vanished after insert conflict"));
    if (!existing.getRequestHash().equals(requestHash)) return IdempotencyKeyClaim.conflict();
    if (existing.getStatus() == IdempotencyKeyStatus.COMPLETED)
      return IdempotencyKeyClaim.replay(existing.getResponseStatus(), existing.getResponseBody());

    Instant expiryThreshold = clock.instant().minus(PENDING_LEASE);
    if (existing.getCreatedAt().isBefore(expiryThreshold)
        && keys.reclaimExpired(existing.getIdempotencyKeyId(), requestHash, expiryThreshold) == 1)
      return IdempotencyKeyClaim.claimed(existing.getIdempotencyKeyId());
    return IdempotencyKeyClaim.inProgress();
  }

  // Joins the caller's own transaction (default propagation) - completing the key and saving
  // the caller's actual result must commit together or not at all.
  @Transactional
  public void complete(Long idempotencyKeyId, int responseStatus, String responseBody) {
    keys.findById(idempotencyKeyId)
        .ifPresent(
            key -> {
              key.complete(responseStatus, responseBody);
              keys.save(key);
            });
  }

  // Own transaction: if the caller's work failed, its transaction is already rolling back by
  // the time this runs, so this delete needs to commit independently to actually take effect
  // and let a retry with the same key succeed.
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void release(Long idempotencyKeyId) {
    keys.deleteById(idempotencyKeyId);
  }
}
