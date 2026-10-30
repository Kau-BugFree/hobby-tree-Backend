package com.hobbytree.hobbytree.domain.auth.service;

import com.hobbytree.hobbytree.domain.auth.config.AuthProperties;
import com.hobbytree.hobbytree.domain.user.entity.User;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenService {
  public static final long ACCESS_SECONDS = 1800;
  public static final long REFRESH_SECONDS = 1209600;
  private final JwtEncoder encoder;
  private final AuthProperties properties;
  private final Clock clock;

  public JwtTokenService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
    this.encoder = encoder;
    this.properties = properties;
    this.clock = clock;
  }

  public String issue(User user, String sessionId, String type) {
    Instant now = clock.instant();
    var claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .audience(List.of("hobbytree-api"))
            .subject(user.getUserId().toString())
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(type.equals("access") ? ACCESS_SECONDS : REFRESH_SECONDS))
            .claim("token_type", type)
            .claim("sid", sessionId)
            .claim("role", user.getUserRole().name())
            .build();
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims))
        .getTokenValue();
  }
}
