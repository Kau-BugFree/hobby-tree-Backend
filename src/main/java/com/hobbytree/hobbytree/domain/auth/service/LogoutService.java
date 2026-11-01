package com.hobbytree.hobbytree.domain.auth.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.dto.LogoutRequest;
import com.hobbytree.hobbytree.domain.auth.dto.LogoutResponse;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

@Service
public class LogoutService {
  private final JwtDecoder decoder;
  private final RedisAuthStore store;

  public LogoutService(@Qualifier("logoutJwtDecoder") JwtDecoder decoder, RedisAuthStore store) {
    this.decoder = decoder;
    this.store = store;
  }

  public LogoutResponse logout(LogoutRequest request) {
    Jwt jwt;
    long userId;
    String sid;
    try {
      jwt = decoder.decode(request.refreshToken());
      userId = Long.parseLong(jwt.getSubject());
      sid = jwt.getClaimAsString("sid");
      if (userId <= 0
          || sid == null
          || sid.isBlank()
          || jwt.getId() == null
          || jwt.getId().isBlank()
          || !"refresh".equals(jwt.getClaimAsString("token_type"))) {
        throw new AuthException(AuthErrorCode.LOGOUT_TOKEN);
      }
    } catch (JwtException | IllegalArgumentException e) {
      throw new AuthException(AuthErrorCode.LOGOUT_TOKEN);
    }
    store.logoutSession(userId, sid, request.refreshToken());
    return new LogoutResponse(true);
  }
}
