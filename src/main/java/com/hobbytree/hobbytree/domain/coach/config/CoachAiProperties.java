package com.hobbytree.hobbytree.domain.coach.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("coach.ai")
public record CoachAiProperties(
    @DefaultValue("false") boolean enabled,
    URI baseUrl,
    @DefaultValue("") String serviceToken,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("45s") Duration requestTimeout) {
  /**
   * Fails startup for enabled clients with an unsafe origin, missing token, or unbounded timeout.
   */
  public CoachAiProperties {
    if (connectTimeout == null
        || connectTimeout.isNegative()
        || connectTimeout.isZero()
        || requestTimeout == null
        || requestTimeout.isNegative()
        || requestTimeout.isZero()
        || requestTimeout.compareTo(Duration.ofSeconds(120)) > 0) {
      throw new IllegalArgumentException(
          "Coach timeouts must be positive; request timeout <= 120s");
    }
    if (enabled
        && (baseUrl == null
            || baseUrl.getHost() == null
            || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
            || baseUrl.getUserInfo() != null
            || baseUrl.getQuery() != null
            || baseUrl.getFragment() != null
            || !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath()))
            || serviceToken == null
            || serviceToken.isBlank()
            || serviceToken.chars().anyMatch(c -> c < 33 || c > 126))) {
      throw new IllegalArgumentException("Enabled coach requires an HTTP origin and service token");
    }
  }

  @Override
  /** Keeps the server credential out of diagnostics that stringify configuration objects. */
  public String toString() {
    return "CoachAiProperties[redacted]";
  }
}
