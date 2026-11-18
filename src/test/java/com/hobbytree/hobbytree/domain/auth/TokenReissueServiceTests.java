package com.hobbytree.hobbytree.domain.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.dto.ReissueRequest;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.auth.service.*;
import com.hobbytree.hobbytree.domain.user.entity.*;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class TokenReissueServiceTests {
  JwtDecoder decoder = mock(JwtDecoder.class);
  JwtTokenService tokens = mock(JwtTokenService.class);
  RedisAuthStore store = mock(RedisAuthStore.class);
  UserRepository users = mock(UserRepository.class);
  TokenReissueService service;
  User user;
  Instant oldExpiry = Instant.parse("2026-11-01T00:00:00Z");
  Instant nextExpiry = Instant.parse("2026-11-15T00:00:00Z");

  @BeforeEach
  void setup() {
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
    service = new TokenReissueService(decoder, tokens, store, users, manager);

    user = User.socialMember(AuthProvider.KAKAO, "social-id");
    ReflectionTestUtils.setField(user, "userId", 1L);

    Jwt oldToken = mock(Jwt.class);
    when(oldToken.getSubject()).thenReturn("1");
    when(oldToken.getClaimAsString("sid")).thenReturn("session-a");
    when(oldToken.getClaimAsString("token_type")).thenReturn("refresh");
    when(oldToken.getId()).thenReturn("old-jti");
    when(oldToken.getExpiresAt()).thenReturn(oldExpiry);
    when(decoder.decode("old-refresh")).thenReturn(oldToken);

    Jwt nextToken = mock(Jwt.class);
    when(nextToken.getExpiresAt()).thenReturn(nextExpiry);
    when(decoder.decode("next-refresh")).thenReturn(nextToken);

    when(users.findForLogin(1L)).thenReturn(Optional.of(user));
    when(tokens.issue(eq(user), eq("session-a"), eq("access"))).thenReturn("next-access");
    when(tokens.issue(eq(user), eq("session-a"), eq("refresh"))).thenReturn("next-refresh");
  }

  @Test
  void rotatesTokensAfterTheDbTransactionHasAlreadyCommitted() {
    var response = service.reissue(new ReissueRequest("old-refresh"));

    assertThat(response.userId()).isEqualTo(1L);
    assertThat(response.accessToken()).isEqualTo("next-access");
    assertThat(response.refreshToken()).isEqualTo("next-refresh");
    verify(store)
        .rotateRefreshToken(1L, "session-a", "old-refresh", "next-refresh", oldExpiry, nextExpiry);
  }

  @Test
  void suspendedUserIsRejectedBeforeAnyTokenIsIssuedOrRotated() {
    ReflectionTestUtils.setField(user, "accountStatus", AccountStatus.SUSPENDED);
    assertThatThrownBy(() -> service.reissue(new ReissueRequest("old-refresh")))
        .isInstanceOfSatisfying(
            AuthException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.SUSPENDED));
    verifyNoInteractions(tokens, store);
  }

  @Test
  void withdrawnUserIsRejectedBeforeAnyTokenIsIssuedOrRotated() {
    ReflectionTestUtils.setField(user, "accountStatus", AccountStatus.WITHDRAWN);
    assertThatThrownBy(() -> service.reissue(new ReissueRequest("old-refresh")))
        .isInstanceOfSatisfying(
            AuthException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.WITHDRAWN_MEMBER));
    verifyNoInteractions(tokens, store);
  }

  @Test
  void rejectsWhenTheRefreshTokenIsMissingRequiredClaims() {
    Jwt invalid = mock(Jwt.class);
    when(invalid.getSubject()).thenReturn("1");
    when(invalid.getClaimAsString("sid")).thenReturn(null);
    when(decoder.decode("bad-refresh")).thenReturn(invalid);

    assertThatThrownBy(() -> service.reissue(new ReissueRequest("bad-refresh")))
        .isInstanceOfSatisfying(
            AuthException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.REFRESH_TOKEN));
    verifyNoInteractions(users, tokens, store);
  }
}
