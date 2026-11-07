package com.hobbytree.hobbytree.domain.profileimage.service;

import com.hobbytree.hobbytree.domain.profileimage.config.ProfileImageProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

// The one component that actually talks to S3 (presign a PUT URL for API-08, read back the
// linked object's metadata/leading bytes for API-06's verification) - mocked as a whole in
// tests, same pattern as KakaoCoordinateRegionResolver/SocialTokenVerifier.
@Component
public class ProfileImageStorageGateway {
  public record ObjectHead(long contentLength, String contentType) {}

  private final S3Presigner presigner;
  private final S3Client s3;
  private final ProfileImageProperties properties;

  public ProfileImageStorageGateway(
      S3Presigner presigner, S3Client s3, ProfileImageProperties properties) {
    this.presigner = presigner;
    this.s3 = s3;
    this.properties = properties;
  }

  public String presignUpload(String objectKey, String contentType, Duration ttl) {
    var presigned =
        presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(
                    PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .contentType(contentType)
                        .build())
                .build());
    return presigned.url().toString();
  }

  public Optional<ObjectHead> headObject(String objectKey) {
    try {
      var response =
          s3.headObject(
              HeadObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build());
      return Optional.of(new ObjectHead(response.contentLength(), response.contentType()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    }
  }

  // Copies a verified upload to a key the client never had a presigned PUT URL for, so a
  // still-valid presigned URL can't silently swap the content a linked profileImageKey
  // serves after verification already passed.
  public void copyObject(String sourceKey, String destinationKey) {
    s3.copyObject(
        CopyObjectRequest.builder()
            .sourceBucket(properties.bucket())
            .sourceKey(sourceKey)
            .destinationBucket(properties.bucket())
            .destinationKey(destinationKey)
            .build());
  }

  // Reads the first `count` bytes so the caller can check the file's magic-byte signature -
  // the declared Content-Type on the PUT request can't be trusted on its own.
  public byte[] leadingBytes(String objectKey, int count) {
    var request =
        GetObjectRequest.builder()
            .bucket(properties.bucket())
            .key(objectKey)
            .range("bytes=0-" + (count - 1))
            .build();
    try (var stream = s3.getObject(request)) {
      return stream.readAllBytes();
    } catch (IOException e) {
      throw SdkClientException.create("Failed to read profile image object bytes", e);
    }
  }
}
