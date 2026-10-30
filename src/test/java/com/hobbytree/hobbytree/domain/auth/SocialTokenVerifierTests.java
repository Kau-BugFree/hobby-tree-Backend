package com.hobbytree.hobbytree.domain.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.hobbytree.hobbytree.domain.auth.config.AuthProperties;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.auth.service.SocialTokenVerifier;
import com.hobbytree.hobbytree.domain.user.entity.AuthProvider;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SocialTokenVerifierTests {
  MockRestServiceServer server;
  SocialTokenVerifier verifier;

  @BeforeEach
  void setup() {
    var builder = RestClient.builder().baseUrl("https://kapi.kakao.com");
    server = MockRestServiceServer.bindTo(builder).build();
    verifier =
        new SocialTokenVerifier(
            builder.build(),
            mock(JwtDecoder.class),
            new AuthProperties(123, "google", "unused", "hobbytree"));
  }

  @Test
  void validatesAppAndFetchesVerifiedUser() {
    server
        .expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
        .andExpect(header("Authorization", "Bearer test-token"))
        .andRespond(
            withSuccess(
                "{\"app_id\":123,\"id\":456,\"expires_in\":3600}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://kapi.kakao.com/v2/user/me"))
        .andRespond(withSuccess("{\"id\":456}", MediaType.APPLICATION_JSON));
    assertThat(verifier.verify(AuthProvider.KAKAO, "test-token")).isEqualTo("456");
    server.verify();
  }

  @Test
  void rejectsOtherAppsWithoutFetchingUser() {
    server
        .expect(anything())
        .andRespond(
            withSuccess(
                "{\"app_id\":999,\"id\":456,\"expires_in\":3600}", MediaType.APPLICATION_JSON));
    assertCode("AUTH401_1");
    server.verify();
  }

  @Test
  void expiredTokenIsUnauthorized() {
    server.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    assertCode("AUTH401_1");
  }

  @Test
  void providerFailureIsNotInvalidToken() {
    server.expect(anything()).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"code\":-1}"));
    assertCode("AUTH502_1");
  }

  @Test
  void timeoutHasSeparateCode() {
    server.expect(anything()).andRespond(withException(new SocketTimeoutException()));
    assertCode("AUTH504_1");
  }

  @Test
  void malformedProviderResponseIsBadGateway() {
    server.expect(anything()).andRespond(withSuccess("{broken", MediaType.APPLICATION_JSON));
    assertCode("AUTH502_1");
  }

  void assertCode(String code) {
    assertThatThrownBy(() -> verifier.verify(AuthProvider.KAKAO, "test-token"))
        .isInstanceOfSatisfying(
            AuthException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo(code));
  }
}
