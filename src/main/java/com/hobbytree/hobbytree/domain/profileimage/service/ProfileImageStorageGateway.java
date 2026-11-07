package com.hobbytree.hobbytree.domain.profileimage.service;

import com.hobbytree.hobbytree.domain.profileimage.config.ProfileImageProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.context.annotation.Lazy;
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
  public record ObjectHead(long contentLength, String contentType, String eTag) {}

  private final S3Presigner presigner;
  private final S3Client s3;
  private final ProfileImageProperties properties;

  // @Lazy on these two parameters (not just on ProfileImageConfig's @Bean methods - a @Lazy
  // bean definition alone doesn't stop an eagerly-created consumer like this @Component from
  // forcing it to resolve during its own construction) injects a deferring proxy instead of
  // the real client, so Region.of(properties.region())'s validation only runs on first actual
  // use, not at application startup.
  public ProfileImageStorageGateway(
      @Lazy S3Presigner presigner, @Lazy S3Client s3, ProfileImageProperties properties) {
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
      return Optional.of(
          new ObjectHead(response.contentLength(), response.contentType(), response.eTag()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    }
  }

  // Copies a verified upload to a key the client never had a presigned PUT URL for, pinned to
  // the exact version headObject/leadingBytes just validated via copySourceIfMatch(eTag) - the
  // headObject/leadingBytes/copyObject calls aren't atomic, and the source key's presigned PUT
  // URL stays valid for the rest of its TTL, so without this pin a re-PUT in the gap between
  // validation and copy would still slip unvalidated content through. S3 responds 412 if the
  // object changed since, which the caller maps to OBJECT_MISMATCH like any other failure here.
  public void copyObject(String sourceKey, String destinationKey, String eTag) {
    s3.copyObject(
        CopyObjectRequest.builder()
            .sourceBucket(properties.bucket())
            .sourceKey(sourceKey)
            .copySourceIfMatch(eTag)
            .destinationBucket(properties.bucket())
            .destinationKey(destinationKey)
            .build());
  }

  // Reads the first `count` bytes so the caller can check the file's magic-byte signature -
  // the declared Content-Type on the PUT request can't be trusted on its own. Pinned to the
  // same eTag headObject just read, for the same reason copyObject is: S3 responds 412 if the
  // object was replaced since, instead of silently reading new, unvalidated bytes.
  public byte[] leadingBytes(String objectKey, int count, String eTag) {
    var request =
        GetObjectRequest.builder()
            .bucket(properties.bucket())
            .key(objectKey)
            .range("bytes=0-" + (count - 1))
            .ifMatch(eTag)
            .build();
    try (var stream = s3.getObject(request)) {
      return stream.readAllBytes();
    } catch (IOException e) {
      throw SdkClientException.create("Failed to read profile image object bytes", e);
    }
  }
}
