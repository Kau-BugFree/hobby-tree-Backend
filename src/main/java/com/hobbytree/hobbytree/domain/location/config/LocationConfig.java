package com.hobbytree.hobbytree.domain.location.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocationProperties.class)
public class LocationConfig {
  // dapi.kakao.com (Local API, server-to-server REST key) is a different host and credential
  // from kapi.kakao.com (user-token verification) in AuthConfig - the two are unrelated Kakao
  // developer-console concepts even though both live under one app.
  @Bean
  public RestClient kakaoLocalRestClient() {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(2));
    return RestClient.builder().baseUrl("https://dapi.kakao.com").requestFactory(factory).build();
  }
}
