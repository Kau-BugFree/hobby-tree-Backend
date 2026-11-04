package com.hobbytree.hobbytree.domain.experience;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class ExperienceIntegrationTests extends IntegrationTestSupport {

  long experience(String listingStatus) {
    return jdbc.queryForObject(
        "INSERT INTO experiences(experience_type,title,listing_status) VALUES ('CLASS',?,?)"
            + " RETURNING experience_id",
        Long.class,
        "class-open-test-" + UUID.randomUUID(),
        listingStatus);
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    // experiences isn't owned by a user (no cascade from deleting the user), so it must be
    // cleaned up explicitly; a title-prefix match is simpler than tracking each seeded id.
    jdbc.update("DELETE FROM experiences WHERE title LIKE 'class-open-test-%'");
    super.cleanupSeededHobbies(userId);
  }

  String activeToken() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return signedIn.path("accessToken").asText();
  }

  HttpResponse<String> subscribeToClassOpen(String accessToken, long experienceId)
      throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/v1/experiences/"
                        + experienceId
                        + "/open-notification"))
            .POST(HttpRequest.BodyPublishers.noBody());
    if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void createsNewSubscriptionThenReturnsExistingOnRepeat() throws Exception {
    String token = activeToken();
    long experienceId = experience("COMING_SOON");

    var first = subscribeToClassOpen(token, experienceId);
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(first.headers().firstValue("Content-Type")).contains("application/json");
    var envelope = mapper.readTree(first.body());
    // Lock the exact wire contract a frontend client codes against: no missing/extra fields,
    // and the Korean message text travels intact (not mangled) over the wire.
    assertThat(fieldNames(envelope))
        .containsExactlyInAnyOrder("isSuccess", "code", "message", "result");
    assertThat(envelope.path("isSuccess").asBoolean()).isTrue();
    assertThat(envelope.path("code").asText()).isEqualTo("SUBSCRIPTION201_1");
    assertThat(envelope.path("message").asText()).isEqualTo("클래스 오픈 알림이 신청되었습니다.");
    var firstBody = envelope.path("result");
    assertThat(fieldNames(firstBody))
        .containsExactlyInAnyOrder("experienceId", "isSubscribed", "channel", "subscribedAt");
    assertThat(firstBody.path("experienceId").asLong()).isEqualTo(experienceId);
    assertThat(firstBody.path("isSubscribed").asBoolean()).isTrue();
    assertThat(firstBody.path("channel").asText()).isEqualTo("IN_APP");
    String subscribedAt = firstBody.path("subscribedAt").asText();
    assertThat(java.time.Instant.parse(subscribedAt)).isNotNull();

    // A repeat request must return the same subscription, not create a second row.
    var second = subscribeToClassOpen(token, experienceId);
    assertThat(second.statusCode()).isEqualTo(200);
    var secondEnvelope = mapper.readTree(second.body());
    assertThat(secondEnvelope.path("code").asText()).isEqualTo("SUBSCRIPTION200_1");
    assertThat(secondEnvelope.path("message").asText()).isEqualTo("이미 클래스 오픈 알림을 신청했습니다.");
    assertThat(result(second).path("subscribedAt").asText()).isEqualTo(subscribedAt);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_subscriptions"
                    + " WHERE experience_id=? AND subscription_type='CLASS_OPEN'",
                Integer.class,
                experienceId))
        .isEqualTo(1);
  }

  List<String> fieldNames(tools.jackson.databind.JsonNode node) {
    return new java.util.ArrayList<>(node.propertyNames());
  }

  @Test
  void rejectsExperiencesThatAreNotComingSoon() throws Exception {
    String token = activeToken();
    for (String status : List.of("DRAFT", "OPEN", "CLOSED", "CANCELLED")) {
      var response = subscribeToClassOpen(token, experience(status));
      assertThat(response.statusCode()).isEqualTo(409);
      var errorEnvelope = mapper.readTree(response.body());
      assertThat(fieldNames(errorEnvelope))
          .containsExactlyInAnyOrder("isSuccess", "code", "message", "result");
      assertThat(errorEnvelope.path("isSuccess").asBoolean()).isFalse();
      assertThat(errorEnvelope.path("code").asText()).isEqualTo("SUBSCRIPTION409_1");
      assertThat(errorEnvelope.path("message").asText()).isEqualTo("이미 오픈했거나 오픈 알림 신청 대상이 아님");
      assertThat(errorEnvelope.path("result").isNull()).isTrue();
    }
  }

  @Test
  void rejectsMissingExperience() throws Exception {
    String token = activeToken();
    var response = subscribeToClassOpen(token, 9223372036854775807L);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("EXPERIENCE404_1");
  }

  @Test
  void requiresAuthenticationAndRejectsPendingOnboardingUser() throws Exception {
    long experienceId = experience("COMING_SOON");
    assertThat(subscribeToClassOpen(null, experienceId).statusCode()).isEqualTo(401);

    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    var response = subscribeToClassOpen(signedIn.path("accessToken").asText(), experienceId);
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_2");
  }

  @Test
  void serializesConcurrentRequestsForSameExperience() throws Exception {
    String token = activeToken();
    long experienceId = experience("COMING_SOON");

    // Two requests racing for the same user+experience must not both see "no subscription
    // yet" and race the uq_sub_class partial unique index; the user-row lock in the service
    // serializes them so exactly one row is ever created.
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<HttpResponse<String>> call =
          () -> {
            start.await();
            return subscribeToClassOpen(token, experienceId);
          };
      var a = executor.submit(call);
      var b = executor.submit(call);
      start.countDown();
      var first = a.get(30, TimeUnit.SECONDS);
      var second = b.get(30, TimeUnit.SECONDS);
      assertThat(List.of(first.statusCode(), second.statusCode()))
          .containsExactlyInAnyOrder(200, 201);
      assertThat(result(first).path("subscribedAt").asText())
          .isEqualTo(result(second).path("subscribedAt").asText());
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_subscriptions"
                    + " WHERE experience_id=? AND subscription_type='CLASS_OPEN'",
                Integer.class,
                experienceId))
        .isEqualTo(1);
  }
}
