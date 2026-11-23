package com.hobbytree.hobbytree.global.ratelimit;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.global.exception.RateLimitExceededException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// Exercises the real Lua script against the local dev Redis (same container other domains'
// integration tests use), without needing the full Spring context - RateLimiter only depends on
// StringRedisTemplate, not the DB. Gated like every other Redis-backed test: plain `./gradlew
// test` (no Redis available) must not try to connect.
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class RateLimiterTests {
  private static LettuceConnectionFactory factory;
  private RateLimiter limiter;
  private StringRedisTemplate redis;
  private String bucket;

  @BeforeAll
  static void startConnection() {
    factory = new LettuceConnectionFactory("127.0.0.1", 16379);
    factory.afterPropertiesSet();
  }

  @AfterAll
  static void stopConnection() {
    factory.destroy();
  }

  @BeforeEach
  void setup() {
    redis = new StringRedisTemplate(factory);
    redis.afterPropertiesSet();
    limiter = new RateLimiter(redis);
    bucket = "test-rate-limiter-" + UUID.randomUUID();
  }

  @AfterEach
  void cleanup() {
    redis.delete(redis.keys("ratelimit:" + bucket + ":*"));
  }

  @Test
  void allowsUpToTheLimitThenRejectsWithAPositiveRetryAfter() {
    for (int i = 0; i < 3; i++) limiter.require(bucket, 1L, 3, Duration.ofSeconds(5));
    assertThatThrownBy(() -> limiter.require(bucket, 1L, 3, Duration.ofSeconds(5)))
        .isInstanceOfSatisfying(
            RateLimitExceededException.class,
            e -> assertThat(e.getRetryAfterSeconds()).isPositive());
  }

  @Test
  void onlySharesABudgetWithinTheSameBucketAndSubject() {
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
    limiter.require(bucket, 1L, 1, Duration.ofMillis(300));
    assertThatThrownBy(() -> limiter.require(bucket, 1L, 1, Duration.ofMillis(300)))
        .isInstanceOf(RateLimitExceededException.class);
    Thread.sleep(400);
    assertThatCode(() -> limiter.require(bucket, 1L, 1, Duration.ofMillis(300)))
        .doesNotThrowAnyException();
  }
}
