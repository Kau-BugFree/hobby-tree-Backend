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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RestoreService {
  private final JwtTokenService tokens;
  private final RedisAuthStore store;
  private final UserRepository users;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public RestoreService(
      JwtTokenService tokens,
      RedisAuthStore store,
      UserRepository users,
      Clock clock,
      PlatformTransactionManager manager) {
    this.tokens = tokens;
    this.store = store;
    this.users = users;
    this.clock = clock;
    this.transaction = new TransactionTemplate(manager);
  }

  // Carries the DB transaction's result out so the Redis session write below can happen after
  // the transaction (and its row lock) has already committed - see issue #185/#269.
  private record Issued(User user, String sid, String access, String refresh) {}

  public RestoreResponse restore(RestoreRequest request) {
    // Step 1 (API-04): validate the restore token's purpose, TTL, and stored hash. This Redis
    // call runs before any transaction opens, matching login/reissue's external-call-first order.
    long userId = parseUserId(request.restoreToken());
    store.consumeRestoreToken(userId, request.restoreToken());

    Issued issued =
        transaction.execute(
            status -> {
              // Step 2 (API-04): the linked account must still be WITHDRAWN and within
              // restorableUntil.
              User user =
                  users
                      .findForLogin(userId)
                      .orElseThrow(() -> new AuthException(AuthErrorCode.NOT_RESTORABLE));
              if (user.getAccountStatus() != AccountStatus.WITHDRAWN)
                throw new AuthException(AuthErrorCode.NOT_RESTORABLE);
              if (user.getRestorableUntil() == null
                  || !clock.instant().isBefore(user.getRestorableUntil()))
                throw new AuthException(AuthErrorCode.RESTORE_WINDOW_EXPIRED);

              user.restore();
              String sid = UUID.randomUUID().toString();
              String access = tokens.issue(user, sid, "access");
              String refresh = tokens.issue(user, sid, "refresh");
              return new Issued(user, sid, access, refresh);
            });

    // Outside the transaction: the row lock taken by findForLogin above was released when the
    // transaction committed, so this blocking Redis call no longer holds a pooled DB connection.
    store.replaceSession(
        issued.user().getUserId(),
        issued.sid(),
        issued.refresh(),
        clock.instant().plusSeconds(JwtTokenService.REFRESH_SECONDS).getEpochSecond());

    return new RestoreResponse(
        issued.user().getUserId(),
        issued.access(),
        issued.refresh(),
        "Bearer",
        JwtTokenService.ACCESS_SECONDS,
        JwtTokenService.REFRESH_SECONDS,
        issued.user().getAccountStatus(),
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
