package com.hobbytree.hobbytree.domain.profileimage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.profileimage.config.ProfileImageProperties;
import com.hobbytree.hobbytree.domain.profileimage.service.ProfileImageStorageGateway;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class ProfileImageSigningTests {
  @Test
  void signsPrivateGetAndPutOfflineWithSyntheticCredentialsAndBoundedExpiry() {
    try (var signer =
        S3Presigner.builder()
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("synthetic-id", "synthetic-secret")))
            .build()) {
      var s3 = mock(S3Client.class);
      var gateway =
          new ProfileImageStorageGateway(
              signer, s3, new ProfileImageProperties("test-profile-bucket", "ap-northeast-2"));
      assertThat(gateway.presignDownload("profiles/1/final/test.jpg", Duration.ofMinutes(15)))
          .startsWith(
              "https://test-profile-bucket.s3.ap-northeast-2.amazonaws.com/profiles/1/final/test.jpg?")
          .contains("X-Amz-Expires=900", "X-Amz-Signature=");
      assertThat(gateway.presignUpload("profiles/1/test.jpg", "image/jpeg", Duration.ofMinutes(5)))
          .contains("X-Amz-Expires=300", "X-Amz-Signature=");
      verifyNoInteractions(s3);
    }
  }

  @Test
  void rejectsMissingConfigurationBeforeTouchingLazyClients() {
    var signer = mock(S3Presigner.class);
    var s3 = mock(S3Client.class);
    var gateway = new ProfileImageStorageGateway(signer, s3, new ProfileImageProperties("", ""));
    assertThatThrownBy(
            () -> gateway.presignUpload("profiles/1/test.jpg", "image/jpeg", Duration.ofMinutes(5)))
        .isInstanceOf(SdkClientException.class);
    assertThatThrownBy(
            () -> gateway.presignDownload("profiles/1/final/test.jpg", Duration.ofMinutes(15)))
        .isInstanceOf(SdkClientException.class);
    verifyNoInteractions(signer, s3);
  }
}
