package com.hobbytree.hobbytree.domain.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.auth.dto.*;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.auth.service.*;
import com.hobbytree.hobbytree.domain.user.entity.*;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SocialLoginServiceTests {
  UserRepository users = mock(UserRepository.class);
  SocialTokenVerifier verifier = mock(SocialTokenVerifier.class);
  JwtTokenService tokens = mock(JwtTokenService.class);
  RedisAuthStore store = mock(RedisAuthStore.class);
  Instant now = Instant.parse("2026-10-30T00:00:00Z");
  SocialLoginService service;
  User user;
  SocialLoginRequest request = new SocialLoginRequest(AuthProvider.KAKAO, "test-token");

  @BeforeEach
  void setup() {
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
    service =
        new SocialLoginService(
            users, verifier, tokens, store, Clock.fixed(now, ZoneOffset.UTC), manager);
    user = User.socialMember(AuthProvider.KAKAO, "verified-id");
    ReflectionTestUtils.setField(user, "userId", 1L);
    when(verifier.verify(AuthProvider.KAKAO, "test-token")).thenReturn("verified-id");
    when(users.findByProviderAndProviderUserId(AuthProvider.KAKAO, "verified-id"))
        .thenReturn(Optional.of(user));
    when(users.findForLogin(1L)).thenReturn(Optional.of(user));
    when(tokens.issue(eq(user), anyString(), eq("access"))).thenReturn("access");
    when(tokens.issue(eq(user), anyString(), eq("refresh"))).thenReturn("refresh");
  }

  @Test
  void existingMemberGetsSession() {
    assertThat(service.login(request).isNewUser()).isFalse();
    verify(store)
        .replaceSession(
            eq(1L), anyString(), eq("refresh"), eq(now.plusSeconds(1209600).getEpochSecond()));
  }

  @Test
  void newMemberIsPendingOnboarding() {
    when(users.findByProviderAndProviderUserId(any(), anyString())).thenReturn(Optional.empty());
    when(users.saveAndFlush(any())).thenReturn(user);
    var result = service.login(request);
    assertThat(result.isNewUser()).isTrue();
    assertThat(result.memberStatus()).isEqualTo(AccountStatus.PENDING_ONBOARDING);
  }

  @Test
  void duplicateInsertRequeriesWinner() {
    when(users.findByProviderAndProviderUserId(any(), anyString()))
        .thenReturn(Optional.empty(), Optional.of(user));
    when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
    assertThat(service.login(request).isNewUser()).isFalse();
    verify(users, times(2)).findByProviderAndProviderUserId(any(), anyString());
  }

  @Test
  void suspendedUserGetsNoSession() {
    ReflectionTestUtils.setField(user, "accountStatus", AccountStatus.SUSPENDED);
    assertCode("AUTH403_1");
    verifyNoInteractions(tokens, store);
  }

  @Test
  void withdrawnUserReceivesOnlyRecoveryToken() {
    ReflectionTestUtils.setField(user, "accountStatus", AccountStatus.WITHDRAWN);
    ReflectionTestUtils.setField(user, "restorableUntil", now.plusSeconds(100));
    when(store.createRestoreToken(1L, Duration.ofSeconds(100))).thenReturn("restore");
    assertThatThrownBy(() -> service.login(request))
        .isInstanceOfSatisfying(
            AuthException.class,
            e -> {
              var guide = (RestoreGuide) e.getResult();
              assertThat(guide.restorable()).isTrue();
              assertThat(guide.restoreTokenExpiresIn()).isEqualTo(100);
            });
    verifyNoInteractions(tokens);
    verify(store, never()).replaceSession(any(), any(), any(), anyLong());
  }

  @Test
  void exactRecoveryDeadlineIsRejected() {
    ReflectionTestUtils.setField(user, "accountStatus", AccountStatus.WITHDRAWN);
    ReflectionTestUtils.setField(user, "restorableUntil", now);
    assertThatThrownBy(() -> service.login(request))
        .isInstanceOfSatisfying(
            AuthException.class,
            e -> {
              var guide = (RestoreGuide) e.getResult();
              assertThat(guide.restorable()).isFalse();
              assertThat(guide.restoreToken()).isNull();
            });
    verifyNoInteractions(store, tokens);
  }

  void assertCode(String code) {
    assertThatThrownBy(() -> service.login(request))
        .isInstanceOfSatisfying(
            AuthException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo(code));
  }
}
