package com.hobbytree.hobbytree.domain.localbusiness.config;

import java.time.Duration;
import java.util.ArrayList;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.AbstractHttpMessageConverter;
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
    return acceptTextPlainJson(
            RestClient.builder()
                .baseUrl("https://naverapihub.apigw.ntruss.com")
                .requestFactory(factory))
        .build();
  }

  // GET /search/v1/local answers with a valid JSON body but Content-Type: text/plain (a known
  // quirk of Naver's search APIs) - without this, the default JSON converter rejects it with
  // UnknownContentTypeException. Public/static so the mock-server test exercises this exact
  // converter configuration, not a separately built RestClient.
  public static RestClient.Builder acceptTextPlainJson(RestClient.Builder builder) {
    return builder.configureMessageConverters(
        clientBuilder ->
            clientBuilder
                .registerDefaults()
                .configureMessageConverters(
                    converter -> {
                      if (converter instanceof AbstractHttpMessageConverter<?> abstractConverter
                          && abstractConverter
                              .getSupportedMediaTypes()
                              .contains(MediaType.APPLICATION_JSON)) {
                        var expanded = new ArrayList<>(abstractConverter.getSupportedMediaTypes());
                        expanded.add(MediaType.TEXT_PLAIN);
                        abstractConverter.setSupportedMediaTypes(expanded);
                      }
                    }));
  }
}
