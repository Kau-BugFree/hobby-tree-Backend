package com.hobbytree.hobbytree.domain.auth.security;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthFilter extends OncePerRequestFilter {
  private final JwtDecoder decoder;
  private final RedisAuthStore store;
  private final UserRepository users;
  private final AuthResponseWriter writer;

  public AuthFilter(
      JwtDecoder decoder, RedisAuthStore store, UserRepository users, AuthResponseWriter writer) {
    this.decoder = decoder;
    this.store = store;
    this.users = users;
    this.writer = writer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      if ("POST".equals(request.getMethod())
          && "/api/v1/auth/social-login".equals(request.getServletPath())) {
        // getRemoteAddr() is the real client IP here: the ec2 profile's Tomcat
        // RemoteIpValve (application-ec2.yml) resolves it from X-Forwarded-For,
        // but only once it verifies the directly connected peer is the ALB.
        // The default profile has no trusted proxy, so it stays the raw peer address.
        store.checkLoginRate(request.getRemoteAddr());
      } else if (!("POST".equals(request.getMethod())
          && ("/api/v1/auth/reissue".equals(request.getServletPath())
              || "/api/v1/auth/logout".equals(request.getServletPath())))) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
          if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7))
            throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
          Jwt jwt = decoder.decode(authorization.substring(7));
          String sid = jwt.getClaimAsString("sid");
          if (!"access".equals(jwt.getClaimAsString("token_type")) || sid == null || sid.isBlank())
            throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
          Long userId;
          try {
            userId = Long.valueOf(jwt.getSubject());
          } catch (RuntimeException e) {
            throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
          }
          if (!store.isCurrentSession(userId, sid))
            throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
          var user =
              users
                  .findById(userId)
                  .orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
          if (user.getAccountStatus() == AccountStatus.SUSPENDED)
            throw new AuthException(AuthErrorCode.SUSPENDED);
          if (user.getAccountStatus() != AccountStatus.ACTIVE
              && user.getAccountStatus() != AccountStatus.PENDING_ONBOARDING)
            throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
          SecurityContextHolder.getContext()
              .setAuthentication(
                  new UsernamePasswordAuthenticationToken(
                      userId,
                      null,
                      List.of(new SimpleGrantedAuthority("ROLE_" + user.getUserRole().name()))));
        }
      }
    } catch (AuthException e) {
      SecurityContextHolder.clearContext();
      writer.write(response, e.getErrorCode(), e.getResult(), e.getRetryAfter());
      return;
    } catch (JwtException e) {
      SecurityContextHolder.clearContext();
      writer.write(response, AuthErrorCode.ACCESS_TOKEN, null, null);
      return;
    } catch (DataAccessException e) {
      SecurityContextHolder.clearContext();
      writer.write(response, GeneralErrorCode.COMMON_INTERNAL_SERVER_ERROR, null, null);
      return;
    }
    chain.doFilter(request, response);
  }
}
