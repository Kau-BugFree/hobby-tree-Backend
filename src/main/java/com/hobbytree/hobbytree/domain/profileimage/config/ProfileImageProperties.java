package com.hobbytree.hobbytree.domain.profileimage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("profile-image")
public record ProfileImageProperties(String bucket, String region) {

  // Direct S3 object URL, no CDN in front yet. If a CDN domain is added later, this is the
  // one place that needs to change - every profileImageKey-to-URL call site goes through here.
  public String resolveUrl(String objectKey) {
    if (objectKey == null) return null;
    return "https://%s.s3.%s.amazonaws.com/%s".formatted(bucket, region, objectKey);
  }
}
