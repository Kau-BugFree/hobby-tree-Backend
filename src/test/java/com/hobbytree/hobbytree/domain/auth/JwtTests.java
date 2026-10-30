package com.hobbytree.hobbytree.domain.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.hobbytree.hobbytree.domain.auth.config.*;
import com.hobbytree.hobbytree.domain.auth.service.JwtTokenService;
import com.hobbytree.hobbytree.domain.user.entity.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class JwtTests {
  AuthConfig config = new AuthConfig();
  AuthProperties properties =
      new AuthProperties(
          123, "google-client", Base64.getEncoder().encodeToString(new byte[64]), "hobbytree");

  @Test
  void serviceTokensHaveDistinctTypesAndExpectedLifetimes() {
    var key = config.serviceSigningKey(properties);
    var decoder = config.serviceJwtDecoder(key, properties);
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    var service =
        new JwtTokenService(
            config.serviceJwtEncoder(key), properties, Clock.fixed(now, ZoneOffset.UTC));
    var user = User.socialMember(AuthProvider.KAKAO, "social-id");
    ReflectionTestUtils.setField(user, "userId", 1L);
    var access = decoder.decode(service.issue(user, "session-a", "access"));
    var refresh = decoder.decode(service.issue(user, "session-a", "refresh"));
    assertThat(access.getClaimAsString("token_type")).isEqualTo("access");
    assertThat(refresh.getClaimAsString("token_type")).isEqualTo("refresh");
    assertThat(access.getExpiresAt()).isEqualTo(now.plusSeconds(1800));
    assertThat(refresh.getExpiresAt()).isEqualTo(now.plusSeconds(1209600));
    assertThat(access.getClaimAsString("sid")).isEqualTo("session-a");
    assertThatThrownBy(() -> decoder.decode("broken.jwt.value")).isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsShortSigningKeys() {
    assertThatThrownBy(
            () -> config.serviceSigningKey(new AuthProperties(123, "google", "YQ==", "hobbytree")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void googleSignatureIssuerAudienceAndExpiryAreAllVerified() throws Exception {
    RSAKey key = new RSAKeyGenerator(2048).keyID("google-test-key").generate();
    var rest = new RestTemplate();
    var server = MockRestServiceServer.bindTo(rest).build();
    server
        .expect(anything())
        .andRespond(
            withSuccess(new JWKSet(key.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));
    var decoder = config.googleJwtDecoder(properties, rest);
    assertThat(
            decoder
                .decode(
                    googleToken(
                        key,
                        "https://accounts.google.com",
                        "google-client",
                        Instant.now().plusSeconds(300)))
                .getSubject())
        .isEqualTo("verified-sub");
    assertThatThrownBy(
            () ->
                decoder.decode(
                    googleToken(
                        key,
                        "https://evil.example",
                        "google-client",
                        Instant.now().plusSeconds(300))))
        .isInstanceOf(JwtException.class);
    assertThatThrownBy(
            () ->
                decoder.decode(
                    googleToken(
                        key,
                        "accounts.google.com",
                        "other-client",
                        Instant.now().plusSeconds(300))))
        .isInstanceOf(JwtException.class);
    assertThatThrownBy(
            () ->
                decoder.decode(
                    googleToken(
                        key,
                        "accounts.google.com",
                        "google-client",
                        Instant.now().minusSeconds(10))))
        .isInstanceOf(JwtException.class);
    RSAKey wrong = new RSAKeyGenerator(2048).keyID("google-test-key").generate();
    assertThatThrownBy(
            () ->
                decoder.decode(
                    googleToken(
                        wrong,
                        "accounts.google.com",
                        "google-client",
                        Instant.now().plusSeconds(300))))
        .isInstanceOf(JwtException.class);
    server.verify();
  }

  String googleToken(RSAKey key, String issuer, String audience, Instant expiry) {
    var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(),
                JwtClaimsSet.builder()
                    .issuer(issuer)
                    .subject("verified-sub")
                    .audience(List.of(audience))
                    .issuedAt(Instant.now().minusSeconds(20))
                    .expiresAt(expiry)
                    .build()))
        .getTokenValue();
  }
}
