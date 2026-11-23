package com.hobbytree.hobbytree.global.ratelimit;

import com.hobbytree.hobbytree.global.exception.RateLimitExceededException;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Reusable per-(bucket, subject) call budget, for any domain whose cost is high enough to need one
 * (#268) - mirrors RedisAuthStore's own INCR/PEXPIRE rate check, generalized so each caller can
 * pick its own limit and window instead of sharing auth's fixed 20-per-60s budget.
 */
@Component
public class RateLimiter {
  private static final DefaultRedisScript<Long> SCRIPT =
      new DefaultRedisScript<>(
          "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end; if n>tonumber(ARGV[2]) then return redis.call('PTTL',KEYS[1]) end; return 0",
          Long.class);

  private final StringRedisTemplate redis;

  public RateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * Each (bucket, subjectId) pair owns an independent budget, so exhausting one endpoint's limit
   * never blocks another, nor one user's calls another user's.
   */
  public void require(String bucket, Object subjectId, int limit, Duration window) {
    String key = "ratelimit:" + bucket + ":" + subjectId;
    Long wait =
        redis.execute(
            SCRIPT, List.of(key), String.valueOf(window.toMillis()), String.valueOf(limit));
    if (wait == null) throw new IllegalStateException("Redis rate limiter returned no result");
    if (wait != 0) throw new RateLimitExceededException(Math.max(1, (wait + 999) / 1000));
  }
}
