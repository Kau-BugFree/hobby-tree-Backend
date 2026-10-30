package com.hobbytree.hobbytree.domain.auth.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {
  @Bean
  public Clock authClock() {
    return Clock.systemUTC();
  }

  @Bean
  public RestClient kakaoRestClient() {
    return RestClient.builder()
        .baseUrl("https://kapi.kakao.com")
        .requestFactory(requestFactory())
        .build();
  }

  private SimpleClientHttpRequestFactory requestFactory() {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(2));
    return factory;
  }

  @Bean
  public RestTemplate googleRestTemplate() {
    return new RestTemplate(requestFactory());
  }

  @Bean
  public JwtDecoder googleJwtDecoder(AuthProperties properties, RestTemplate googleRestTemplate) {
    var decoder =
        NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
            .restOperations(googleRestTemplate)
            .build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO),
            jwt -> {
              boolean valid =
                  ("https://accounts.google.com".equals(jwt.getClaimAsString("iss"))
                          || "accounts.google.com".equals(jwt.getClaimAsString("iss")))
                      && jwt.getAudience() != null
                      && jwt.getAudience().contains(properties.googleClientId())
                      && jwt.getExpiresAt() != null
                      && jwt.getSubject() != null
                      && !jwt.getSubject().isBlank();
              return valid
                  ? OAuth2TokenValidatorResult.success()
                  : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }));
    return decoder;
  }

  @Bean
  public SecretKeySpec serviceSigningKey(AuthProperties properties) {
    byte[] key;
    try {
      key = Base64.getDecoder().decode(properties.jwtSecret());
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("JWT_SECRET must be a Base64 encoded random key");
    }
    if (key.length < 64)
      throw new IllegalArgumentException("JWT_SECRET must contain at least 64 random bytes");
    return new SecretKeySpec(key, "HmacSHA256");
  }

  @Bean
  public JwtEncoder serviceJwtEncoder(SecretKeySpec serviceSigningKey) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(serviceSigningKey));
  }

  @Bean
  public JwtDecoder logoutJwtDecoder(
      SecretKeySpec serviceSigningKey, AuthProperties properties, Clock clock) {
    var decoder =
        NimbusJwtDecoder.withSecretKey(serviceSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
    // Logout alone may accept expired tokens, but still verifies signature, issuer,
    // audience and not-before. Redis must independently prove actual token issuance.
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtIssuerValidator(properties.issuer()),
            jwt -> {
              var now = clock.instant();
              boolean valid =
                  jwt.getExpiresAt() != null
                      && jwt.getIssuedAt() != null
                      && !jwt.getIssuedAt().isAfter(now)
                      && (jwt.getNotBefore() == null || !jwt.getNotBefore().isAfter(now))
                      && jwt.getAudience() != null
                      && jwt.getAudience().contains("hobbytree-api");
              return valid
                  ? OAuth2TokenValidatorResult.success()
                  : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }));
    return decoder;
  }

  @Bean
  public JwtDecoder serviceJwtDecoder(SecretKeySpec serviceSigningKey, AuthProperties properties) {
    var decoder =
        NimbusJwtDecoder.withSecretKey(serviceSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO),
            new JwtIssuerValidator(properties.issuer()),
            jwt ->
                jwt.getExpiresAt() != null
                        && jwt.getAudience() != null
                        && jwt.getAudience().contains("hobbytree-api")
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    return decoder;
  }
}
