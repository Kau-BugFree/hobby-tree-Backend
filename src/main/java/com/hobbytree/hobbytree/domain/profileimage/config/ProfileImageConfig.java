package com.hobbytree.hobbytree.domain.profileimage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProfileImageProperties.class)
public class ProfileImageConfig {
  // Unlike LocationConfig's RestClient (whose builder never inspects the key string),
  // Region.of() validates eagerly and throws IllegalArgumentException on a blank region - so
  // an empty default would crash the whole application context at startup, not just leave
  // this feature unusable. @Lazy defers that construction (and the validation) until
  // something actually calls through ProfileImageStorageGateway, matching the same "unset
  // means this path alone is unavailable" intent as KakaoCoordinateRegionResolver's empty
  // default, instead of hardcoding a region that would silently mean nothing.
  @Bean
  @Lazy
  public S3Presigner s3Presigner(ProfileImageProperties properties) {
    return S3Presigner.builder().region(Region.of(properties.region())).build();
  }

  @Bean
  @Lazy
  public S3Client profileImageS3Client(ProfileImageProperties properties) {
    return S3Client.builder().region(Region.of(properties.region())).build();
  }
}
