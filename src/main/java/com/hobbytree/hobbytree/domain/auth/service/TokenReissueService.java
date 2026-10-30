package com.hobbytree.hobbytree.domain.auth.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.dto.ReissueRequest;
import com.hobbytree.hobbytree.domain.auth.dto.ReissueResponse;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenReissueService {
  private final JwtDecoder decoder;
  private final JwtTokenService tokens;
  private final RedisAuthStore store;
  private final UserRepository users;

  public TokenReissueService(
      @Qualifier("serviceJwtDecoder") JwtDecoder decoder,
      JwtTokenService tokens,
      RedisAuthStore store,
      UserRepository users) {
    this.decoder = decoder;
    this.tokens = tokens;
    this.store = store;
    this.users = users;
  }

  @Transactional
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

    // Serialize against login so an old session cannot overwrite a newly logged-in device.
    var user =
        users
            .findForLogin(userId)
            .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN));
    if (user.getAccountStatus() == AccountStatus.SUSPENDED)
      throw new AuthException(AuthErrorCode.SUSPENDED);
    if (user.getAccountStatus() == AccountStatus.WITHDRAWN)
      throw new AuthException(AuthErrorCode.WITHDRAWN_MEMBER);
    if (user.getAccountStatus() != AccountStatus.ACTIVE
        && user.getAccountStatus() != AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.REFRESH_TOKEN);

    String access = tokens.issue(user, sid, "access");
    String nextRefresh = tokens.issue(user, sid, "refresh");
    var nextExpiry = decoder.decode(nextRefresh).getExpiresAt();
    store.rotateRefreshToken(
        userId, sid, request.refreshToken(), nextRefresh, refresh.getExpiresAt(), nextExpiry);
    return new ReissueResponse(
        userId,
        access,
        nextRefresh,
        "Bearer",
        JwtTokenService.ACCESS_SECONDS,
        JwtTokenService.REFRESH_SECONDS,
        user.getAccountStatus());
  }
}
