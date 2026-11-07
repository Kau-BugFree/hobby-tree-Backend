package com.hobbytree.hobbytree.domain.idempotency.service;

// Result of IdempotencyKeyService.claim(). Exactly one of the four outcomes applies; only
// CLAIMED carries idempotencyKeyId (what the caller must later complete()/release()), and
// only REPLAY carries the cached response to hand straight back to the client.
public record IdempotencyKeyClaim(
    Outcome outcome, Long idempotencyKeyId, Integer responseStatus, String responseBody) {

  public enum Outcome {
    CLAIMED,
    CONFLICT,
    IN_PROGRESS,
    REPLAY
  }

  public static IdempotencyKeyClaim claimed(Long idempotencyKeyId) {
    return new IdempotencyKeyClaim(Outcome.CLAIMED, idempotencyKeyId, null, null);
  }

  public static IdempotencyKeyClaim conflict() {
    return new IdempotencyKeyClaim(Outcome.CONFLICT, null, null, null);
  }

  public static IdempotencyKeyClaim inProgress() {
    return new IdempotencyKeyClaim(Outcome.IN_PROGRESS, null, null, null);
  }

  public static IdempotencyKeyClaim replay(int responseStatus, String responseBody) {
    return new IdempotencyKeyClaim(Outcome.REPLAY, null, responseStatus, responseBody);
  }
}
