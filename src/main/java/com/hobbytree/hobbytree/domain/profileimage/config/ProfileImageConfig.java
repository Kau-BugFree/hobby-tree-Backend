package com.hobbytree.hobbytree.domain.profileimage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProfileImageProperties.class)
public class ProfileImageConfig {
  // Neither builder validates the region string or touches the network at construction time
  // (same reasoning as LocationConfig's RestClient) - the app must still boot with an empty
  // region when these beans are never exercised (ProfileImageStorageGateway mocked in tests,
  // same pattern as KakaoCoordinateRegionResolver).
  @Bean
  public S3Presigner s3Presigner(ProfileImageProperties properties) {
    return S3Presigner.builder().region(Region.of(properties.region())).build();
  }

  @Bean
  public S3Client profileImageS3Client(ProfileImageProperties properties) {
    return S3Client.builder().region(Region.of(properties.region())).build();
  }
}
