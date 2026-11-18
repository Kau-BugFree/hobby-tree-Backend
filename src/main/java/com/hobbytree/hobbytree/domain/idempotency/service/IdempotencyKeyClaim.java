package com.hobbytree.hobbytree.domain.idempotency.service;

// Result of IdempotencyKeyService.claim(). Exactly one of the four outcomes applies; only
// CLAIMED carries idempotencyKeyId/ownershipToken (what the caller must later pass back to
// complete()/release() - the token proves it is still this exact claim, not one a retry has
// since reclaimed), and only REPLAY carries the cached response to hand straight back to the
// client.
public record IdempotencyKeyClaim(
    Outcome outcome,
    Long idempotencyKeyId,
    String ownershipToken,
    Integer responseStatus,
    String responseBody) {

  public enum Outcome {
    CLAIMED,
    CONFLICT,
    IN_PROGRESS,
    REPLAY
  }

  public static IdempotencyKeyClaim claimed(Long idempotencyKeyId, String ownershipToken) {
    return new IdempotencyKeyClaim(Outcome.CLAIMED, idempotencyKeyId, ownershipToken, null, null);
  }

  public static IdempotencyKeyClaim conflict() {
    return new IdempotencyKeyClaim(Outcome.CONFLICT, null, null, null, null);
  }

  public static IdempotencyKeyClaim inProgress() {
    return new IdempotencyKeyClaim(Outcome.IN_PROGRESS, null, null, null, null);
  }

  public static IdempotencyKeyClaim replay(int responseStatus, String responseBody) {
    return new IdempotencyKeyClaim(Outcome.REPLAY, null, null, responseStatus, responseBody);
  }
}
