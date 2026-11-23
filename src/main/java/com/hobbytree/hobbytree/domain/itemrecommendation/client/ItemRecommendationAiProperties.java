package com.hobbytree.hobbytree.domain.itemrecommendation.client;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("item-recommendation.ai")
public record ItemRecommendationAiProperties(
    @DefaultValue("false") boolean enabled,
    URI baseUrl,
    @DefaultValue("") String serviceToken,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("10s") Duration requestTimeout) {
  /**
   * Fails startup for enabled clients with an unsafe origin, missing token, or unbounded timeout.
   * The catalog lookup has no outbound Adpick call on the request path, so the request timeout is
   * bounded tighter than Coach's generative call.
   */
  public ItemRecommendationAiProperties {
    if (connectTimeout == null
        || connectTimeout.isNegative()
        || connectTimeout.isZero()
        || requestTimeout == null
        || requestTimeout.isNegative()
        || requestTimeout.isZero()
        || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
      throw new IllegalArgumentException(
          "Item recommendation timeouts must be positive; request timeout <= 30s");
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
      throw new IllegalArgumentException(
          "Enabled item recommendation client requires an HTTP origin and service token");
    }
  }

  @Override
  /** Keeps the server credential out of diagnostics that stringify configuration objects. */
  public String toString() {
    return "ItemRecommendationAiProperties[redacted]";
  }
}
