package com.hobbytree.hobbytree.domain.auth.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.dto.RestoreRequest;
import com.hobbytree.hobbytree.domain.auth.dto.RestoreResponse;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RestoreService {
  private final JwtTokenService tokens;
  private final RedisAuthStore store;
  private final UserRepository users;
  private final Clock clock;

  public RestoreService(
      JwtTokenService tokens, RedisAuthStore store, UserRepository users, Clock clock) {
    this.tokens = tokens;
    this.store = store;
    this.users = users;
    this.clock = clock;
  }

  @Transactional
  public RestoreResponse restore(RestoreRequest request) {
    // Step 1 (API-04): validate the restore token's purpose, TTL, and stored hash.
    long userId = parseUserId(request.restoreToken());
    store.consumeRestoreToken(userId, request.restoreToken());

    // Step 2 (API-04): the linked account must still be WITHDRAWN and within restorableUntil.
    User user =
        users
            .findForLogin(userId)
            .orElseThrow(() -> new AuthException(AuthErrorCode.NOT_RESTORABLE));
    if (user.getAccountStatus() != AccountStatus.WITHDRAWN)
      throw new AuthException(AuthErrorCode.NOT_RESTORABLE);
    if (user.getRestorableUntil() == null || !clock.instant().isBefore(user.getRestorableUntil()))
      throw new AuthException(AuthErrorCode.RESTORE_WINDOW_EXPIRED);

    user.restore();
    String sid = UUID.randomUUID().toString();
    String access = tokens.issue(user, sid, "access");
    String refresh = tokens.issue(user, sid, "refresh");
    store.replaceSession(
        user.getUserId(),
        sid,
        refresh,
        clock.instant().plusSeconds(JwtTokenService.REFRESH_SECONDS).getEpochSecond());
    return new RestoreResponse(
        user.getUserId(),
        access,
        refresh,
        "Bearer",
        JwtTokenService.ACCESS_SECONDS,
        JwtTokenService.REFRESH_SECONDS,
        user.getAccountStatus(),
        false,
        true);
  }

  // Restore tokens are opaque (not JWTs): "{userId}.{uuid}{uuid}" from
  // RedisAuthStore.createRestoreToken.
  private long parseUserId(String restoreToken) {
    int dot = restoreToken.indexOf('.');
    if (dot <= 0) throw new AuthException(AuthErrorCode.RESTORE_TOKEN);
    try {
      return Long.parseLong(restoreToken.substring(0, dot));
    } catch (NumberFormatException e) {
      throw new AuthException(AuthErrorCode.RESTORE_TOKEN);
    }
  }
}
