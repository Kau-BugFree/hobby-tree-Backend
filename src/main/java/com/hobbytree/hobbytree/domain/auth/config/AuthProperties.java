package com.hobbytree.hobbytree.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("auth")
public record AuthProperties(
    long kakaoAppId, String googleClientId, String jwtSecret, String issuer) {
  public AuthProperties {
    if (kakaoAppId <= 0
        || googleClientId == null
        || googleClientId.isBlank()
        || issuer == null
        || issuer.isBlank())
      throw new IllegalArgumentException("Valid auth provider IDs and issuer are required");
  }

  @Override
  public String toString() {
    return "AuthProperties[redacted]";
  }
}
