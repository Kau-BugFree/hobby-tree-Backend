package com.hobbytree.hobbytree.domain.localbusiness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("naver.local-search")
public record NaverLocalSearchProperties(String clientId, String clientSecret) {
  @Override
  public String toString() {
    return "NaverLocalSearchProperties[redacted]";
  }
}
