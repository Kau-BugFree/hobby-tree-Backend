package com.hobbytree.hobbytree.domain.auth.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.dto.ReissueRequest;
import com.hobbytree.hobbytree.domain.auth.dto.ReissueResponse;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TokenReissueService {
  private final JwtDecoder decoder;
  private final JwtTokenService tokens;
  private final RedisAuthStore store;
  private final UserRepository users;
  private final TransactionTemplate transaction;

  public TokenReissueService(
      @Qualifier("serviceJwtDecoder") JwtDecoder decoder,
      JwtTokenService tokens,
      RedisAuthStore store,
      UserRepository users,
      PlatformTransactionManager manager) {
    this.decoder = decoder;
    this.tokens = tokens;
    this.store = store;
    this.users = users;
    this.transaction = new TransactionTemplate(manager);
  }

  // Carries the DB transaction's result out so the Redis rotation below can happen after the
  // transaction (and its row lock) has already committed - see issue #185.
  private record Issued(User user, String access, String nextRefresh, Instant nextExpiresAt) {}

  public ReissueResponse reissue(ReissueRequest request) {
    Jwt refresh;
    Long userId;
    String sid;
    try {
      refresh = decoder.decode(request.refreshToken());
      userId = Long.valueOf(refresh.getSubject());
      sid = refresh.getClaimAsString("sid");
      if (userId <= 0
          || sid == null
          || sid.isBlank()
          || refresh.getId() == null
          || refresh.getExpiresAt() == null
          || !"refresh".equals(refresh.getClaimAsString("token_type"))) {
        throw new AuthException(AuthErrorCode.REFRESH_TOKEN);
      }
    } catch (JwtException | IllegalArgumentException e) {
      throw new AuthException(AuthErrorCode.REFRESH_TOKEN);
    }

    Long finalUserId = userId;
    String finalSid = sid;
    Issued issued =
        transaction.execute(
            status -> {
              // Serialize against login so a status change mid-reissue is caught before tokens
              // are handed out.
              var user =
                  users
                      .findForLogin(finalUserId)
                      .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN));
              if (user.getAccountStatus() == AccountStatus.SUSPENDED)
                throw new AuthException(AuthErrorCode.SUSPENDED);
              if (user.getAccountStatus() == AccountStatus.WITHDRAWN)
                throw new AuthException(AuthErrorCode.WITHDRAWN_MEMBER);
              if (user.getAccountStatus() != AccountStatus.ACTIVE
                  && user.getAccountStatus() != AccountStatus.PENDING_ONBOARDING)
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN);
              String access = tokens.issue(user, finalSid, "access");
              String nextRefresh = tokens.issue(user, finalSid, "refresh");
              Instant nextExpiresAt = decoder.decode(nextRefresh).getExpiresAt();
              return new Issued(user, access, nextRefresh, nextExpiresAt);
            });

    // Outside the transaction: the row lock taken by findForLogin above was released when the
    // transaction committed, so this blocking Redis call no longer holds a pooled DB connection.
    store.rotateRefreshToken(
        finalUserId,
        finalSid,
        request.refreshToken(),
        issued.nextRefresh(),
        refresh.getExpiresAt(),
        issued.nextExpiresAt());

    return new ReissueResponse(
        finalUserId,
        issued.access(),
        issued.nextRefresh(),
        "Bearer",
        JwtTokenService.ACCESS_SECONDS,
        JwtTokenService.REFRESH_SECONDS,
        issued.user().getAccountStatus());
  }
}
