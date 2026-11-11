package com.hobbytree.hobbytree.domain.recommendation.client;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("recommendation.ai")
public record RecommendationAiProperties(
    @DefaultValue("false") boolean enabled,
    URI baseUrl,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("10s") Duration requestTimeout) {
  public RecommendationAiProperties {
    if (connectTimeout == null
        || connectTimeout.isZero()
        || connectTimeout.isNegative()
        || requestTimeout == null
        || requestTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException("Positive bounded recommendation timeouts required");
    if (enabled
        && (baseUrl == null
            || baseUrl.getHost() == null
            || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
            || baseUrl.getUserInfo() != null
            || baseUrl.getQuery() != null
            || baseUrl.getFragment() != null
            || !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath()))))
      throw new IllegalArgumentException("Enabled recommender requires a fixed HTTP origin");
  }
}
