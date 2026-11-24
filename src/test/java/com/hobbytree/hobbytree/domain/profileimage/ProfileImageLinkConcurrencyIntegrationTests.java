package com.hobbytree.hobbytree.domain.profileimage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.profileimage.service.ProfileImageStorageGateway;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// Separate test class (and so a separate, small-pool Spring context) so this doesn't shrink the
// DB pool for every other *IntegrationTests class - same reasoning as
// AuthRedisBlockingIntegrationTests, but reproducing issue #274's failure mode against
// ProfileImageService.copyToFinalKey's S3 round trip instead of a Redis call, and the review's
// two follow-up concerns: an account status change and a nickname claim racing the same window.
//
// JUnit5 does not reliably inherit @EnabledIfEnvironmentVariable from IntegrationTestSupport in
// a plain build (see UserIntegrationTests), so it must be repeated here.
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class ProfileImageLinkConcurrencyIntegrationTests extends IntegrationTestSupport {
  @MockitoBean private ProfileImageStorageGateway storage;
  private static final int BLOCKED_LINKS = 2;
  private static final byte[] JPEG_MAGIC_BYTES = {
    (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0
  };

  @DynamicPropertySource
  static void twoDbConnectionsAndNoOpenInView(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> String.valueOf(BLOCKED_LINKS));
    registry.add("spring.datasource.hikari.minimum-idle", () -> String.valueOf(BLOCKED_LINKS));
    registry.add("spring.jpa.open-in-view", () -> "false");
  }

  private record ActiveUser(long id, String token) {}

  private ActiveUser activeUser(String loginBody) throws Exception {
    var signedIn = result(login(loginBody));
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return new ActiveUser(id, signedIn.path("accessToken").asText());
  }

  private String issueBody(String fileName, String contentType, Object fileSize) {
    return "{\"fileName\":\"%s\",\"contentType\":\"%s\",\"fileSize\":%s}"
        .formatted(fileName, contentType, fileSize);
  }

  // Stubs the two read-only S3 calls (headObject/leadingBytes) to succeed so each case only
  // needs to control copyObject - the one call this suite blocks on.
  private String issueValidUpload(String token) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/users/me/profile-image/upload-url"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    issueBody("profile.jpg", "image/jpeg", 204800)));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    String objectKey = result(response).path("objectKey").asText();
    when(storage.headObject(objectKey))
        .thenReturn(
            Optional.of(
                new ProfileImageStorageGateway.ObjectHead(204800, "image/jpeg", "etag-valid")));
    when(storage.leadingBytes(objectKey, 12, "etag-valid")).thenReturn(JPEG_MAGIC_BYTES);
    return objectKey;
  }

  private HttpResponse<String> updateProfile(String token, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void copyingToS3DoesNotHoldADbConnectionForEitherConcurrentLink() throws Exception {
    var a = activeUser("{\"provider\":\"KAKAO\",\"providerToken\":\"integration-test-token\"}");
    var b = activeUser("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}");
    String aKey = issueValidUpload(a.token());
    String bKey = issueValidUpload(b.token());

    var ready = new CountDownLatch(BLOCKED_LINKS);
    var release = new CountDownLatch(1);
    var blocked = new AtomicInteger(0);
    doAnswer(
            invocation -> {
              if (blocked.incrementAndGet() <= BLOCKED_LINKS) {
                ready.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).as("test released in time").isTrue();
              }
              return null;
            })
        .when(storage)
        .copyObject(any(), any(), any());

    try (var executor = Executors.newFixedThreadPool(BLOCKED_LINKS)) {
      var pending =
          java.util.List.of(
              executor.submit(
                  (Callable<HttpResponse<String>>)
                      () ->
                          updateProfile(a.token(), "{\"profileImageKey\":\"%s\"}".formatted(aKey))),
              executor.submit(
                  (Callable<HttpResponse<String>>)
                      () ->
                          updateProfile(
                              b.token(), "{\"profileImageKey\":\"%s\"}".formatted(bKey))));
      assertThat(ready.await(5, TimeUnit.SECONDS))
          .as("both links reached the blocked S3 call")
          .isTrue();

      // Both pool connections are now, at most, held by the two links above up to the point
      // their prepare transaction committed - if either still held its connection here (the
      // pre-fix behavior), the pool would have zero free connections and this unrelated request
      // would time out instead of completing.
      var unrelated =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me"))
                  .header("Authorization", "Bearer " + a.token())
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(unrelated.statusCode()).isEqualTo(200);

      release.countDown();
      for (var future : pending) {
        var response = future.get(5, TimeUnit.SECONDS);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      }
    }
  }

  @Test
  void rejectsLinkingWhenTheAccountIsSuspendedDuringTheS3Window() throws Exception {
    var user = activeUser("{\"provider\":\"KAKAO\",\"providerToken\":\"integration-test-token\"}");
    String objectKey = issueValidUpload(user.token());

    var ready = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              ready.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).as("test released in time").isTrue();
              return null;
            })
        .when(storage)
        .copyObject(any(), any(), any());

    try (var executor = Executors.newSingleThreadExecutor()) {
      var pending =
          executor.submit(
              (Callable<HttpResponse<String>>)
                  () ->
                      updateProfile(
                          user.token(), "{\"profileImageKey\":\"%s\"}".formatted(objectKey)));
      assertThat(ready.await(5, TimeUnit.SECONDS)).as("link reached the blocked S3 call").isTrue();

      // The prepare phase already committed (and saw ACTIVE) before reaching the blocked call
      // above - this update only takes effect for the finish phase that runs after release.
      jdbc.update("UPDATE users SET account_status='SUSPENDED' WHERE user_id=?", user.id());
      release.countDown();

      var response = pending.get(5, TimeUnit.SECONDS);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
      assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_1");

      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM profile_image_uploads WHERE object_key=?",
                  String.class,
                  objectKey))
          .isEqualTo("ISSUED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT profile_image_key FROM users WHERE user_id=?", String.class, user.id()))
          .isNull();
    }
  }

  @Test
  void rejectsLinkingWhenAnotherRequestClaimsTheSameNicknameDuringTheS3Window() throws Exception {
    var linking =
        activeUser("{\"provider\":\"KAKAO\",\"providerToken\":\"integration-test-token\"}");
    var claiming =
        activeUser("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}");
    String objectKey = issueValidUpload(linking.token());

    var ready = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              ready.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).as("test released in time").isTrue();
              return null;
            })
        .when(storage)
        .copyObject(any(), any(), any());

    try (var executor = Executors.newSingleThreadExecutor()) {
      var pending =
          executor.submit(
              (Callable<HttpResponse<String>>)
                  () ->
                      updateProfile(
                          linking.token(),
                          "{\"nickname\":\"시험다람쥐\",\"profileImageKey\":\"%s\"}"
                              .formatted(objectKey)));
      assertThat(ready.await(5, TimeUnit.SECONDS)).as("link reached the blocked S3 call").isTrue();

      // The linking request's prepare phase already passed its nickname pre-check (the name was
      // still free) before reaching the blocked call above - this claims it for real while that
      // request is off copying to S3.
      var claimResponse = updateProfile(claiming.token(), "{\"nickname\":\"시험다람쥐\"}");
      assertThat(claimResponse.statusCode()).as(claimResponse.body()).isEqualTo(200);
      release.countDown();

      var response = pending.get(5, TimeUnit.SECONDS);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
      assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("USER409_2");

      // The finish phase's nickname re-check must fail before touching the upload or the user's
      // profileImageKey - both nickname and image are rejected together, not partially applied.
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM profile_image_uploads WHERE object_key=?",
                  String.class,
                  objectKey))
          .isEqualTo("ISSUED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT profile_image_key FROM users WHERE user_id=?",
                  String.class,
                  linking.id()))
          .isNull();
    }
  }
}
