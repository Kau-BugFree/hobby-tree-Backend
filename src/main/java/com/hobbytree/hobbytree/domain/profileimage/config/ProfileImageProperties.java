package com.hobbytree.hobbytree.domain.profileimage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("profile-image")
public record ProfileImageProperties(String bucket, String region) {

  public void requireStorageConfiguration() {
    if (bucket == null
        || region == null
        || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
        || bucket.contains("..")
        || bucket.contains(".-")
        || bucket.contains("-.")
        || bucket.matches("[0-9.]+")
        || !region.matches("[a-z]{2}(?:-[a-z]+)+-[1-9][0-9]*")) {
      throw software.amazon.awssdk.core.exception.SdkClientException.create(
          "Profile image storage configuration is unavailable");
    }
  }
}
