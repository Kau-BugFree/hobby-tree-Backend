package com.hobbytree.hobbytree.global.ratelimit;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.global.exception.RateLimitExceededException;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

// Exercises the real RateLimiter bean against the app's own configured Redis connection (host,
// port, password, database - whatever IntegrationTestSupport/the real app resolve them to),
// instead of a hand-built connection that would skip the ACL the real deployment enforces.
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class RateLimiterIntegrationTests extends IntegrationTestSupport {
  @Autowired private RateLimiter limiter;
  private String bucket;

  @AfterEach
  void cleanupRateLimiterKeys() {
    redis.delete(redis.keys("auth:ratelimit:" + bucket + ":*"));
  }

  private void newBucket() {
    bucket = "test-rate-limiter-" + UUID.randomUUID();
  }

  @Test
  void allowsUpToTheLimitThenRejectsWithAPositiveRetryAfter() {
    newBucket();
    for (int i = 0; i < 3; i++) limiter.require(bucket, 1L, 3, Duration.ofSeconds(5));
    assertThatThrownBy(() -> limiter.require(bucket, 1L, 3, Duration.ofSeconds(5)))
        .isInstanceOfSatisfying(
            RateLimitExceededException.class,
            e -> assertThat(e.getRetryAfterSeconds()).isPositive());
  }

  @Test
  void onlySharesABudgetWithinTheSameBucketAndSubject() {
    newBucket();
    limiter.require(bucket, 1L, 1, Duration.ofSeconds(5));
    assertThatThrownBy(() -> limiter.require(bucket, 1L, 1, Duration.ofSeconds(5)))
        .isInstanceOf(RateLimitExceededException.class);
    // A different subject under the same bucket has its own budget.
    assertThatCode(() -> limiter.require(bucket, 2L, 1, Duration.ofSeconds(5)))
        .doesNotThrowAnyException();
    // A different bucket for the same subject has its own budget too.
    assertThatCode(() -> limiter.require(bucket + "-other", 1L, 1, Duration.ofSeconds(5)))
        .doesNotThrowAnyException();
  }

  @Test
  void theBudgetResetsOnceTheWindowExpires() throws InterruptedException {
    newBucket();
    limiter.require(bucket, 1L, 1, Duration.ofMillis(300));
    assertThatThrownBy(() -> limiter.require(bucket, 1L, 1, Duration.ofMillis(300)))
        .isInstanceOf(RateLimitExceededException.class);
    Thread.sleep(400);
    assertThatCode(() -> limiter.require(bucket, 1L, 1, Duration.ofMillis(300)))
        .doesNotThrowAnyException();
  }

  @Test
  void keysStayUnderTheAuthAclPrefix() {
    newBucket();
    limiter.require(bucket, 1L, 5, Duration.ofSeconds(5));
    assertThat(redis.keys("auth:ratelimit:" + bucket + ":*")).isNotEmpty();
  }
}
