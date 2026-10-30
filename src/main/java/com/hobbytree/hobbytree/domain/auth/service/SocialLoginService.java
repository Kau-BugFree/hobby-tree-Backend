package com.hobbytree.hobbytree.domain.auth.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.dto.*;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.user.entity.*;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SocialLoginService {
  private final UserRepository users;
  private final SocialTokenVerifier verifier;
  private final JwtTokenService tokens;
  private final RedisAuthStore store;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public SocialLoginService(
      UserRepository users,
      SocialTokenVerifier verifier,
      JwtTokenService tokens,
      RedisAuthStore store,
      Clock clock,
      PlatformTransactionManager manager) {
    this.users = users;
    this.verifier = verifier;
    this.tokens = tokens;
    this.store = store;
    this.clock = clock;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  private record Member(Long id, boolean created) {}

  public SocialLoginResponse login(SocialLoginRequest request) {
    String socialId = verifier.verify(request.provider(), request.providerToken());
    Member member;
    try {
      member =
          transaction.execute(
              status ->
                  users
                      .findByProviderAndProviderUserId(request.provider(), socialId)
                      .map(u -> new Member(u.getUserId(), false))
                      .orElseGet(
                          () ->
                              new Member(
                                  users
                                      .saveAndFlush(User.socialMember(request.provider(), socialId))
                                      .getUserId(),
                                  true)));
    } catch (DataIntegrityViolationException failure) {
      // The failed insert transaction must be rolled back before the concurrent winner is read.
      member =
          transaction.execute(
              status ->
                  users
                      .findByProviderAndProviderUserId(request.provider(), socialId)
                      .map(u -> new Member(u.getUserId(), false))
                      .orElseThrow(() -> failure));
    }
    Member resolved = member;
    return transaction.execute(
        status -> {
          User user =
              users
                  .findForLogin(resolved.id())
                  .orElseThrow(() -> new AuthException(AuthErrorCode.SOCIAL_TOKEN));
          if (user.getAccountStatus() == AccountStatus.SUSPENDED)
            throw new AuthException(AuthErrorCode.SUSPENDED);
          if (user.getAccountStatus() == AccountStatus.WITHDRAWN) throw withdrawn(user);
          if (user.getAccountStatus() != AccountStatus.ACTIVE
              && user.getAccountStatus() != AccountStatus.PENDING_ONBOARDING)
            throw new AuthException(AuthErrorCode.SOCIAL_TOKEN);
          String sid = UUID.randomUUID().toString();
          String access = tokens.issue(user, sid, "access");
          String refresh = tokens.issue(user, sid, "refresh");
          store.replaceSession(
              user.getUserId(),
              sid,
              refresh,
              clock.instant().plusSeconds(JwtTokenService.REFRESH_SECONDS).getEpochSecond());
          return new SocialLoginResponse(
              user.getUserId(),
              access,
              refresh,
              "Bearer",
              JwtTokenService.ACCESS_SECONDS,
              JwtTokenService.REFRESH_SECONDS,
              user.getAccountStatus(),
              resolved.created());
        });
  }

  private AuthException withdrawn(User user) {
    long seconds =
        user.getRestorableUntil() == null
            ? 0
            : Math.min(
                300, Duration.between(clock.instant(), user.getRestorableUntil()).getSeconds());
    boolean restorable = seconds > 0;
    String restore =
        restorable ? store.createRestoreToken(user.getUserId(), Duration.ofSeconds(seconds)) : null;
    return new AuthException(
        AuthErrorCode.WITHDRAWN,
        new RestoreGuide(
            AccountStatus.WITHDRAWN,
            restorable,
            user.getRestorableUntil(),
            restore,
            Math.max(0, seconds)),
        null);
  }
}
