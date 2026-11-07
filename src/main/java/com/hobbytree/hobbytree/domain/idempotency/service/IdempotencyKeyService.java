package com.hobbytree.hobbytree.domain.idempotency.service;

import com.hobbytree.hobbytree.domain.idempotency.entity.IdempotencyKey;
import com.hobbytree.hobbytree.domain.idempotency.entity.IdempotencyKeyStatus;
import com.hobbytree.hobbytree.domain.idempotency.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
  private final IdempotencyKeyRepository keys;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IdempotencyKeyClaim claim(
      Long userId, String endpoint, String idempotencyKey, String requestHash) {
    var existing = keys.findByUserIdAndEndpointAndIdempotencyKey(userId, endpoint, idempotencyKey);
    if (existing.isPresent()) {
      IdempotencyKey key = existing.get();
      if (!key.getRequestHash().equals(requestHash)) return IdempotencyKeyClaim.conflict();
      if (key.getStatus() == IdempotencyKeyStatus.PENDING) return IdempotencyKeyClaim.inProgress();
      return IdempotencyKeyClaim.replay(key.getResponseStatus(), key.getResponseBody());
    }
    try {
      IdempotencyKey issued =
          keys.saveAndFlush(IdempotencyKey.issue(userId, endpoint, idempotencyKey, requestHash));
      return IdempotencyKeyClaim.claimed(issued.getIdempotencyKeyId());
    } catch (DataIntegrityViolationException e) {
      // Lost the race to a concurrent request claiming the same (user, endpoint, key) -
      // caught here, not rethrown, so this transaction still commits normally (there is
      // nothing of this caller's left to roll back).
      return IdempotencyKeyClaim.inProgress();
    }
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
