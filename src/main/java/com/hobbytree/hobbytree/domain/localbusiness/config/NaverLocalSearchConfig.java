package com.hobbytree.hobbytree.domain.localbusiness.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NaverLocalSearchProperties.class)
public class NaverLocalSearchConfig {
  // NAVER API HUB (NAVER Cloud Platform), not the legacy Naver Developers "검색" product -
  // different host and credential headers (X-NCP-APIGW-API-KEY-ID/-KEY, not
  // X-Naver-Client-Id/-Secret).
  @Bean
  public RestClient naverLocalSearchRestClient() {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(3));
    return RestClient.builder()
        .baseUrl("https://naverapihub.apigw.ntruss.com")
        .requestFactory(factory)
        .build();
  }
}
