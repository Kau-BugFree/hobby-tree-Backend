package com.hobbytree.hobbytree.domain.profileimage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.profileimage.config.ProfileImageProperties;
import com.hobbytree.hobbytree.domain.profileimage.service.ProfileImageStorageGateway;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.core.exception.SdkClientException;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class ProfileImageIntegrationTests extends IntegrationTestSupport {
  @MockitoBean private ProfileImageStorageGateway storage;
  @Autowired private ProfileImageProperties properties;

  private static final byte[] JPEG_MAGIC_BYTES = {
    (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0
  };

  String activeToken() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return signedIn.path("accessToken").asText();
  }

  long currentUserId() {
    return jdbc.queryForObject(
        "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);
  }

  HttpResponse<String> issueUploadUrl(String token, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/users/me/profile-image/upload-url"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> resetProfileImage(String token) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/users/me/profile-image"))
            .timeout(Duration.ofSeconds(10))
            .DELETE();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> updateProfile(String token, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> getMyProfile(String token) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me")).GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  String issueBody(String fileName, String contentType, Object fileSize) {
    return "{\"fileName\":\"%s\",\"contentType\":\"%s\",\"fileSize\":%s}"
        .formatted(fileName, contentType, fileSize);
  }

  // Issues a real upload URL and stubs the gateway so the object looks like a valid uploaded
  // JPEG, returning the objectKey the server generated.
  String issueAndStubValidObject(String token) throws Exception {
    when(storage.presignUpload(anyString(), anyString(), any()))
        .thenReturn("https://uploads.example.com/signed");
    var response = issueUploadUrl(token, issueBody("profile.jpg", "image/jpeg", 204800));
    String objectKey = result(response).path("objectKey").asText();
    when(storage.headObject(objectKey))
        .thenReturn(
            Optional.of(
                new ProfileImageStorageGateway.ObjectHead(204800, "image/jpeg", "etag-valid")));
    when(storage.leadingBytes(objectKey, 12, "etag-valid")).thenReturn(JPEG_MAGIC_BYTES);
    return objectKey;
  }

  @Test
  void issuesUploadUrlAndReturnsTheExactContract() throws Exception {
    String token = activeToken();
    when(storage.presignUpload(anyString(), eq("image/jpeg"), eq(Duration.ofMinutes(5))))
        .thenReturn("https://uploads.example.com/profiles/1/uuid.jpg?signature=example");

    var response = issueUploadUrl(token, issueBody("profile.jpg", "image/jpeg", 204800));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var envelope = mapper.readTree(response.body());
    assertThat(envelope.path("code").asText()).isEqualTo("USER200_4");
    assertThat(envelope.path("message").asText()).isEqualTo("프로필 이미지 업로드 URL이 발급되었습니다.");
    var result = envelope.path("result");
    assertThat(new java.util.ArrayList<>(result.propertyNames()))
        .containsExactlyInAnyOrder(
            "uploadUrl", "objectKey", "method", "expiresIn", "requiredHeaders", "maxFileSize");
    assertThat(result.path("uploadUrl").asText())
        .isEqualTo("https://uploads.example.com/profiles/1/uuid.jpg?signature=example");
    assertThat(result.path("objectKey").asText()).matches("profiles/\\d+/[\\w-]+\\.jpg");
    assertThat(result.path("method").asText()).isEqualTo("PUT");
    assertThat(result.path("expiresIn").asInt()).isEqualTo(300);
    assertThat(result.path("requiredHeaders").path("Content-Type").asText())
        .isEqualTo("image/jpeg");
    assertThat(result.path("maxFileSize").asLong()).isEqualTo(5242880L);

    long rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM profile_image_uploads WHERE status='ISSUED' AND object_key=?",
            Long.class,
            result.path("objectKey").asText());
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void rejectsUnsupportedFileNameOrContentType() throws Exception {
    String token = activeToken();
    for (String body :
        List.of(
            issueBody("", "image/jpeg", 100),
            issueBody("a".repeat(101), "image/jpeg", 100),
            issueBody("profile.gif", "image/gif", 100))) {
      var response = issueUploadUrl(token, body);
      assertThat(response.statusCode()).as(body).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(body)
          .isEqualTo("UPLOAD400_2");
    }
    assertThat(
            issueUploadUrl(token, "{\"fileName\":\"profile.jpg\",\"fileSize\":100}").statusCode())
        .isEqualTo(400);
  }

  @Test
  void rejectsMissingOrNonPositiveFileSize() throws Exception {
    String token = activeToken();
    for (String body :
        List.of(
            "{\"fileName\":\"profile.jpg\",\"contentType\":\"image/jpeg\"}",
            issueBody("profile.jpg", "image/jpeg", 0),
            issueBody("profile.jpg", "image/jpeg", -1))) {
      var response = issueUploadUrl(token, body);
      assertThat(response.statusCode()).as(body).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(body)
          .isEqualTo("COMMON400_1");
    }
  }

  @Test
  void rejectsOversizedFileSize() throws Exception {
    String token = activeToken();
    var response = issueUploadUrl(token, issueBody("profile.jpg", "image/jpeg", 5_242_881L));
    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("UPLOAD413_1");
  }

  @Test
  void returnsIssuanceUnavailableWhenPresignFails() throws Exception {
    String token = activeToken();
    when(storage.presignUpload(anyString(), anyString(), any()))
        .thenThrow(SdkClientException.create("boom"));

    var response = issueUploadUrl(token, issueBody("profile.jpg", "image/jpeg", 204800));
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("UPLOAD503_1");
  }

  @Test
  void linksProfileImageKeyThroughApi06AfterValidatingTheObject() throws Exception {
    String token = activeToken();
    String objectKey = issueAndStubValidObject(token);
    long userId = currentUserId();

    var response = updateProfile(token, "{\"profileImageKey\":\"%s\"}".formatted(objectKey));
    assertThat(response.statusCode()).isEqualTo(200);
    String profileImageUrl = result(response).path("profileImageUrl").asText();
    // The stored/served URL must be the copied final key, not the original presigned-PUT
    // target - linking the upload key directly would let a still-valid presigned URL swap
    // the content after this verification already passed.
    assertThat(profileImageUrl).isNotEqualTo(properties.resolveUrl(objectKey));
    assertThat(profileImageUrl).matches(".*/profiles/" + userId + "/final/[\\w-]+\\.jpg$");

    var captor = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(storage).copyObject(eq(objectKey), captor.capture(), eq("etag-valid"));
    assertThat(profileImageUrl).isEqualTo(properties.resolveUrl(captor.getValue()));

    var profile = result(getMyProfile(token));
    assertThat(profile.path("profileImageUrl").asText()).isEqualTo(profileImageUrl);

    // The original upload-target row is still tracked as LINKED (so it can't be linked
    // again elsewhere), even though it's no longer what the profile actually points to.
    long linkedRows =
        jdbc.queryForObject(
            "SELECT count(*) FROM profile_image_uploads WHERE status='LINKED' AND object_key=?",
            Long.class,
            objectKey);
    assertThat(linkedRows).isEqualTo(1);
  }

  @Test
  void rejectsLinkingAKeyNotIssuedToThisUser() throws Exception {
    String token = activeToken();
    var response = updateProfile(token, "{\"profileImageKey\":\"profiles/999/never-issued.jpg\"}");
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("UPLOAD403_1");
  }

  @Test
  void rejectsLinkingWhenObjectMissingFromStorage() throws Exception {
    String token = activeToken();
    when(storage.presignUpload(anyString(), anyString(), any()))
        .thenReturn("https://uploads.example.com/signed");
    var issued = issueUploadUrl(token, issueBody("profile.jpg", "image/jpeg", 204800));
    String objectKey = result(issued).path("objectKey").asText();
    when(storage.headObject(objectKey)).thenReturn(Optional.empty());

    var response = updateProfile(token, "{\"profileImageKey\":\"%s\"}".formatted(objectKey));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("UPLOAD400_1");
  }

  @Test
  void rejectsLinkingWhenObjectExceedsMaxSizeOrWrongContentType() throws Exception {
    String token = activeToken();
    when(storage.presignUpload(anyString(), anyString(), any()))
        .thenReturn("https://uploads.example.com/signed");

    var tooLarge = issueUploadUrl(token, issueBody("a.jpg", "image/jpeg", 204800));
    String tooLargeKey = result(tooLarge).path("objectKey").asText();
    when(storage.headObject(tooLargeKey))
        .thenReturn(
            Optional.of(
                new ProfileImageStorageGateway.ObjectHead(5_242_881L, "image/jpeg", "etag-big")));
    assertThat(
            updateProfile(token, "{\"profileImageKey\":\"%s\"}".formatted(tooLargeKey))
                .statusCode())
        .isEqualTo(400);

    var wrongType = issueUploadUrl(token, issueBody("b.jpg", "image/jpeg", 204800));
    String wrongTypeKey = result(wrongType).path("objectKey").asText();
    when(storage.headObject(wrongTypeKey))
        .thenReturn(
            Optional.of(
                new ProfileImageStorageGateway.ObjectHead(204800, "image/png", "etag-wrong")));
    var response = updateProfile(token, "{\"profileImageKey\":\"%s\"}".formatted(wrongTypeKey));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("UPLOAD400_1");
  }

  @Test
  void rejectsLinkingWhenMagicBytesDontMatchDeclaredType() throws Exception {
    String token = activeToken();
    when(storage.presignUpload(anyString(), anyString(), any()))
        .thenReturn("https://uploads.example.com/signed");
    var issued = issueUploadUrl(token, issueBody("profile.jpg", "image/jpeg", 204800));
    String objectKey = result(issued).path("objectKey").asText();
    when(storage.headObject(objectKey))
        .thenReturn(
            Optional.of(
                new ProfileImageStorageGateway.ObjectHead(
                    204800, "image/jpeg", "etag-wrong-bytes")));
    when(storage.leadingBytes(objectKey, 12, "etag-wrong-bytes"))
        .thenReturn(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});

    var response = updateProfile(token, "{\"profileImageKey\":\"%s\"}".formatted(objectKey));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("UPLOAD400_1");
  }

  @Test
  void rejectsLinkingAnEmptyObject() throws Exception {
    String token = activeToken();
    when(storage.presignUpload(anyString(), anyString(), any()))
        .thenReturn("https://uploads.example.com/signed");
    var issued = issueUploadUrl(token, issueBody("profile.jpg", "image/jpeg", 204800));
    String objectKey = result(issued).path("objectKey").asText();
    // A presigned PUT URL doesn't enforce a minimum body length, so a 0-byte upload is a
    // real scenario to defend against, not just a theoretical one.
    when(storage.headObject(objectKey))
        .thenReturn(
            Optional.of(new ProfileImageStorageGateway.ObjectHead(0, "image/jpeg", "etag-empty")));

    var response = updateProfile(token, "{\"profileImageKey\":\"%s\"}".formatted(objectKey));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("UPLOAD400_1");
    verify(storage, never()).leadingBytes(any(), anyInt(), any());
  }

  @Test
  void mapsAnS3FailureWhileReadingTheObjectToObjectMismatchInsteadOfAServerError()
      throws Exception {
    String token = activeToken();
    when(storage.presignUpload(anyString(), anyString(), any()))
        .thenReturn("https://uploads.example.com/signed");
    var issued = issueUploadUrl(token, issueBody("profile.jpg", "image/jpeg", 204800));
    String objectKey = result(issued).path("objectKey").asText();
    when(storage.headObject(objectKey))
        .thenReturn(
            Optional.of(
                new ProfileImageStorageGateway.ObjectHead(204800, "image/jpeg", "etag-s3fail")));
    // Simulates S3's real response (416 InvalidRange) if an empty/short object ever slips
    // past the size check above - this must not surface as a generic 500.
    when(storage.leadingBytes(objectKey, 12, "etag-s3fail"))
        .thenThrow(
            software.amazon.awssdk.services.s3.model.S3Exception.builder()
                .message("InvalidRange")
                .statusCode(416)
                .build());

    var response = updateProfile(token, "{\"profileImageKey\":\"%s\"}".formatted(objectKey));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("UPLOAD400_1");
  }

  @Test
  void rejectsLinkingWhenTheObjectIsReplacedBetweenVerificationAndCopy() throws Exception {
    String token = activeToken();
    when(storage.presignUpload(anyString(), anyString(), any()))
        .thenReturn("https://uploads.example.com/signed");
    var issued = issueUploadUrl(token, issueBody("profile.jpg", "image/jpeg", 204800));
    String objectKey = result(issued).path("objectKey").asText();
    when(storage.headObject(objectKey))
        .thenReturn(
            Optional.of(
                new ProfileImageStorageGateway.ObjectHead(204800, "image/jpeg", "etag-race")));
    when(storage.leadingBytes(objectKey, 12, "etag-race")).thenReturn(JPEG_MAGIC_BYTES);
    // The source key's presigned PUT URL is still valid for the rest of its TTL, so a client
    // could re-PUT different content right after headObject/leadingBytes passed but before
    // copyObject runs. S3 enforces copySourceIfMatch and responds 412 when that happens.
    doThrow(
            software.amazon.awssdk.services.s3.model.S3Exception.builder()
                .message("PreconditionFailed")
                .statusCode(412)
                .build())
        .when(storage)
        .copyObject(eq(objectKey), any(), eq("etag-race"));

    var response = updateProfile(token, "{\"profileImageKey\":\"%s\"}".formatted(objectKey));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("UPLOAD400_1");

    // Never left ISSUED either - a retry with the same key must still fail ownership/state
    // checks rather than accidentally being treated as a fresh attempt.
    long linkedRows =
        jdbc.queryForObject(
            "SELECT count(*) FROM profile_image_uploads WHERE status='ISSUED' AND object_key=?",
            Long.class,
            objectKey);
    assertThat(linkedRows).isEqualTo(1);
  }

  @Test
  void resetsProfileImageAndClearsItFromProfile() throws Exception {
    String token = activeToken();
    String objectKey = issueAndStubValidObject(token);
    updateProfile(token, "{\"profileImageKey\":\"%s\"}".formatted(objectKey));

    var response = resetProfileImage(token);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var envelope = mapper.readTree(response.body());
    assertThat(envelope.path("code").asText()).isEqualTo("USER200_5");
    assertThat(envelope.path("message").asText()).isEqualTo("프로필 이미지가 초기화되었습니다.");
    var result = envelope.path("result");
    assertThat(new java.util.ArrayList<>(result.propertyNames()))
        .containsExactlyInAnyOrder("userId", "profileImageUrl", "updatedAt");
    assertThat(result.path("profileImageUrl").isNull()).isTrue();
    assertThat(result.path("updatedAt").asText()).isNotBlank();

    var profile = result(getMyProfile(token));
    assertThat(profile.path("profileImageUrl").isNull()).isTrue();
  }

  @Test
  void resetIsIdempotentWhenAlreadyDefault() throws Exception {
    String token = activeToken();
    var response = resetProfileImage(token);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("USER200_5");
  }

  @Test
  void requiresAuthenticationForBothEndpoints() throws Exception {
    assertThat(issueUploadUrl(null, issueBody("profile.jpg", "image/jpeg", 100)).statusCode())
        .isEqualTo(401);
    assertThat(resetProfileImage(null).statusCode()).isEqualTo(401);
  }
}
