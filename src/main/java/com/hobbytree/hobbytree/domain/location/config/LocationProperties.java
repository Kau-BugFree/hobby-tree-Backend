package com.hobbytree.hobbytree.domain.location.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("location")
public record LocationProperties(String kakaoRestApiKey) {
  @Override
  public String toString() {
    return "LocationProperties[redacted]";
  }
}
