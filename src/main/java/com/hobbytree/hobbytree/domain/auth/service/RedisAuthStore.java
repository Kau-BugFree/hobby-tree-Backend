package com.hobbytree.hobbytree.domain.auth.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisAuthStore {
  private final StringRedisTemplate redis;

  public RedisAuthStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  private static final DefaultRedisScript<Long> RATE =
      new DefaultRedisScript<>(
          "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],60000) end; if n>20 then return redis.call('PTTL',KEYS[1]) end; return 0",
          Long.class);
  private static final DefaultRedisScript<Long> SESSION =
      new DefaultRedisScript<>(
          "redis.call('HSET',KEYS[1],'sid',ARGV[1],'refreshHash',ARGV[2],'expiresAt',ARGV[3]); redis.call('EXPIRE',KEYS[1],ARGV[4]); return 1",
          Long.class);

  private static final DefaultRedisScript<Long> ROTATE =
      new DefaultRedisScript<>(
          """
      local currentSid = redis.call('HGET', KEYS[1], 'sid')
      if not currentSid or currentSid ~= ARGV[1] then return 0 end
      local now = redis.call('TIME')
      local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
      if tonumber(ARGV[4]) <= nowMs or tonumber(ARGV[5]) <= nowMs then return 0 end
      if redis.call('EXISTS', KEYS[2]) == 1 then
          redis.call('DEL', KEYS[1])
          return -1
      end
      if redis.call('HGET', KEYS[1], 'refreshHash') ~= ARGV[2] then return 0 end
      redis.call('SET', KEYS[2], '1', 'PXAT', ARGV[4])
      redis.call('HSET', KEYS[1], 'refreshHash', ARGV[3], 'expiresAt', ARGV[6])
      redis.call('PEXPIREAT', KEYS[1], ARGV[5])
      return 1
      """,
          Long.class);

  public void rotateRefreshToken(
      Long userId,
      String sid,
      String oldToken,
      String newToken,
      java.time.Instant oldExpiresAt,
      java.time.Instant newExpiresAt) {
    String oldHash = hash(oldToken);
    Long result =
        redis.execute(
            ROTATE,
            List.of(
                "auth:session:" + userId,
                "auth:refresh-used:" + userId + ":" + sid + ":" + oldHash),
            sid,
            oldHash,
            hash(newToken),
            Long.toString(oldExpiresAt.toEpochMilli()),
            Long.toString(newExpiresAt.toEpochMilli()),
            Long.toString(newExpiresAt.getEpochSecond()));
    if (result == null)
      throw new IllegalStateException("Redis refresh rotation returned no result");
    if (result != 1) throw new AuthException(AuthErrorCode.REFRESH_TOKEN);
  }

  public void checkLoginRate(String remoteAddress) {
    Long wait = redis.execute(RATE, List.of("auth:login-rate:" + hash(remoteAddress)));
    if (wait == null) throw new IllegalStateException("Redis rate limiter returned no result");
    if (wait != 0)
      throw new AuthException(AuthErrorCode.RATE_LIMIT, null, Math.max(1, (wait + 999) / 1000));
  }

  public void replaceSession(Long userId, String sid, String refreshToken, long expiresAt) {
    Long result =
        redis.execute(
            SESSION,
            List.of("auth:session:" + userId),
            sid,
            hash(refreshToken),
            Long.toString(expiresAt),
            Long.toString(JwtTokenService.REFRESH_SECONDS));
    if (result == null || result != 1)
      throw new IllegalStateException("Redis session write failed");
  }

  public boolean isCurrentSession(Long userId, String sid) {
    return sid.equals(redis.opsForHash().get("auth:session:" + userId, "sid"));
  }

  public String createRestoreToken(Long userId, Duration ttl) {
    String token = userId + "." + UUID.randomUUID() + UUID.randomUUID();
    // One current recovery token per user; re-login invalidates the previous token.
    redis.opsForValue().set("auth:restore:" + userId, hash(token), ttl);
    return token;
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }
}
