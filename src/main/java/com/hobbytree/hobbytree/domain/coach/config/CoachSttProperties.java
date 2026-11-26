package com.hobbytree.hobbytree.domain.coach.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("coach.stt")
public record CoachSttProperties(
    @DefaultValue("false") boolean enabled, @DefaultValue("45s") Duration requestTimeout) {
  public CoachSttProperties {
    if (requestTimeout == null
        || requestTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.compareTo(Duration.ofSeconds(60)) > 0)
      throw new IllegalArgumentException("STT request timeout must be positive and <= 60s");
  }
}
