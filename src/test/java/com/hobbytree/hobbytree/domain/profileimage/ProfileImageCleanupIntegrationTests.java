package com.hobbytree.hobbytree.domain.profileimage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.profileimage.service.ProfileImageCleanupScheduler;
import com.hobbytree.hobbytree.domain.profileimage.service.ProfileImageStorageGateway;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// JUnit5 does not reliably inherit @EnabledIfEnvironmentVariable from IntegrationTestSupport in
// a plain build (see UserIntegrationTests), so it must be repeated here.
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class ProfileImageCleanupIntegrationTests extends IntegrationTestSupport {
  @MockitoBean private ProfileImageStorageGateway storage;
  @Autowired private ProfileImageCleanupScheduler scheduler;

  private static final byte[] JPEG_MAGIC_BYTES = {
    (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0
  };

  private String activeToken() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return signedIn.path("accessToken").asText();
  }

  private String issueAndStubValidObject(String token) throws Exception {
    when(storage.presignUpload(anyString(), anyString(), any()))
        .thenReturn("https://uploads.example.com/signed");
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/users/me/profile-image/upload-url"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"fileName\":\"profile.jpg\",\"contentType\":\"image/jpeg\",\"fileSize\":204800}"));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    String objectKey = result(response).path("objectKey").asText();
    when(storage.headObject(objectKey))
        .thenReturn(
            Optional.of(
                new ProfileImageStorageGateway.ObjectHead(204800, "image/jpeg", "etag-valid")));
    when(storage.leadingBytes(objectKey, 12, "etag-valid")).thenReturn(JPEG_MAGIC_BYTES);
    return objectKey;
  }

  private HttpResponse<String> linkProfileImage(String token, String objectKey) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .method(
                "PATCH",
                HttpRequest.BodyPublishers.ofString(
                    "{\"profileImageKey\":\"%s\"}".formatted(objectKey)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String finalKeyOf(String objectKey) {
    return jdbc.queryForObject(
        "SELECT final_key FROM profile_image_uploads WHERE object_key=?", String.class, objectKey);
  }

  @Test
  void sweepDeletesAnExpiredNeverLinkedUploadAndItsRow() throws Exception {
    String token = activeToken();
    String objectKey = issueAndStubValidObject(token);
    jdbc.update(
        "UPDATE profile_image_uploads SET expires_at = now() - interval '2 hours'"
            + " WHERE object_key=?",
        objectKey);

    scheduler.cleanupOrphanedProfileImageObjects();

    verify(storage).deleteObject(objectKey);
    long remaining =
        jdbc.queryForObject(
            "SELECT count(*) FROM profile_image_uploads WHERE object_key=?", Long.class, objectKey);
    assertThat(remaining).isZero();
  }

  @Test
  void sweepCleansTheSourceKeyButKeepsTheRowAndFinalKeyWhileStillCurrent() throws Exception {
    String token = activeToken();
    String objectKey = issueAndStubValidObject(token);
    assertThat(linkProfileImage(token, objectKey).statusCode()).isEqualTo(200);
    String finalKey = finalKeyOf(objectKey);

    scheduler.cleanupOrphanedProfileImageObjects();

    verify(storage).deleteObject(objectKey);
    verify(storage, never()).deleteObject(finalKey);
    long remaining =
        jdbc.queryForObject(
            "SELECT count(*) FROM profile_image_uploads WHERE object_key=? AND status='LINKED'",
            Long.class,
            objectKey);
    assertThat(remaining).isEqualTo(1);
  }

  @Test
  void sweepLeavesASupersededRowAloneWhileItsUploadUrlCouldStillBeValid() throws Exception {
    String token = activeToken();
    String firstKey = issueAndStubValidObject(token);
    assertThat(linkProfileImage(token, firstKey).statusCode()).isEqualTo(200);
    String firstFinalKey = finalKeyOf(firstKey);

    String secondKey = issueAndStubValidObject(token);
    assertThat(linkProfileImage(token, secondKey).statusCode()).isEqualTo(200);

    // firstKey's presigned PUT URL (expires_at, real-world 5 minutes from issuance) has not
    // expired yet - a client could still PUT new content to it, so the row must survive even
    // though it's already superseded. The expiry guard only protects the row (and so,
    // transitively, a recreated source object a later sweep would still find via it) - the
    // final key was never exposed through any presigned URL, so deleting it immediately is
    // safe and expected regardless of expiry.
    scheduler.cleanupOrphanedProfileImageObjects();

    verify(storage).deleteObject(firstFinalKey);
    long firstRemaining =
        jdbc.queryForObject(
            "SELECT count(*) FROM profile_image_uploads WHERE object_key=?", Long.class, firstKey);
    assertThat(firstRemaining).isEqualTo(1);
  }

  @Test
  void sweepDeletesBothKeysAndTheRowOnceReplacedAndTheUploadUrlHasExpired() throws Exception {
    String token = activeToken();
    String firstKey = issueAndStubValidObject(token);
    assertThat(linkProfileImage(token, firstKey).statusCode()).isEqualTo(200);
    String firstFinalKey = finalKeyOf(firstKey);
    // Backdates past the scheduler's own expiry grace, simulating that firstKey's presigned PUT
    // URL has long since expired and can no longer be used to recreate the object.
    jdbc.update(
        "UPDATE profile_image_uploads SET expires_at = now() - interval '2 hours'"
            + " WHERE object_key=?",
        firstKey);

    String secondKey = issueAndStubValidObject(token);
    assertThat(linkProfileImage(token, secondKey).statusCode()).isEqualTo(200);
    String secondFinalKey = finalKeyOf(secondKey);
    assertThat(secondFinalKey).isNotEqualTo(firstFinalKey);

    scheduler.cleanupOrphanedProfileImageObjects();

    verify(storage).deleteObject(firstKey);
    verify(storage).deleteObject(firstFinalKey);
    verify(storage).deleteObject(secondKey);
    verify(storage, never()).deleteObject(secondFinalKey);

    long firstRemaining =
        jdbc.queryForObject(
            "SELECT count(*) FROM profile_image_uploads WHERE object_key=?", Long.class, firstKey);
    assertThat(firstRemaining).isZero();
    long secondRemaining =
        jdbc.queryForObject(
            "SELECT count(*) FROM profile_image_uploads WHERE object_key=? AND status='LINKED'",
            Long.class,
            secondKey);
    assertThat(secondRemaining).isEqualTo(1);
  }

  // Review: "Retain the legacy row's original key when it equals the current profile image" -
  // a row linked before the copy-to-a-separate-final-key design has no final_key, and its own
  // object_key IS the live profileImageKey directly; deleting it would delete the live image.
  @Test
  void sweepNeverDeletesALegacyLinkedUploadsObjectKeyThatIsTheUsersCurrentImageDirectly()
      throws Exception {
    String token = activeToken();
    long userId =
        jdbc.queryForObject(
            "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);
    String legacyKey = "profiles/" + userId + "/legacy-direct.jpg";
    jdbc.update("UPDATE users SET profile_image_key=? WHERE user_id=?", legacyKey, userId);
    jdbc.update(
        "INSERT INTO profile_image_uploads"
            + "(user_id,object_key,content_type,reported_file_size,status,expires_at,final_key)"
            + " VALUES (?,?,?,?,?,now()+interval '5 minutes',NULL)",
        userId,
        legacyKey,
        "image/jpeg",
        204800,
        "LINKED");

    scheduler.cleanupOrphanedProfileImageObjects();

    verifyNoInteractions(storage);
    assertThat(
            jdbc.queryForObject(
                "SELECT profile_image_key FROM users WHERE user_id=?", String.class, userId))
        .isEqualTo(legacyKey);
  }

  // Review: "Retain cleanup records until S3 deletion succeeds" - even with a real DB, a
  // deleteObject failure must not take the row down with it.
  @Test
  void sweepLeavesTheRowAloneWhenDeletingTheExpiredUploadsObjectFails() throws Exception {
    String token = activeToken();
    String objectKey = issueAndStubValidObject(token);
    jdbc.update(
        "UPDATE profile_image_uploads SET expires_at = now() - interval '2 hours'"
            + " WHERE object_key=?",
        objectKey);
    doThrow(new RuntimeException("s3 unavailable")).when(storage).deleteObject(objectKey);

    scheduler.cleanupOrphanedProfileImageObjects();

    long remaining =
        jdbc.queryForObject(
            "SELECT count(*) FROM profile_image_uploads WHERE object_key=?", Long.class, objectKey);
    assertThat(remaining).isEqualTo(1);
  }
}
