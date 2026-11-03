package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class ActivityIntegrationTests extends IntegrationTestSupport {
  Long hobbyId;
  Long stageId;

  long activity(String status) {
    if (hobbyId == null) {
      hobbyId =
          jdbc.queryForObject(
              "INSERT INTO hobbies(hobby_code,hobby_name) VALUES (?, 'Save test') RETURNING hobby_id",
              Long.class,
              "save-" + UUID.randomUUID());
      stageId =
          jdbc.queryForObject(
              "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,0,'Seed') RETURNING hobby_stage_id",
              Long.class,
              hobbyId);
    }
    return jdbc.queryForObject(
        "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status) VALUES (?,?,'Save test',?) RETURNING activity_id",
        Long.class,
        stageId,
        "save-" + UUID.randomUUID(),
        status);
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    if (stageId != null) {
      jdbc.update("DELETE FROM activities WHERE hobby_stage_id=?", stageId);
      jdbc.update("DELETE FROM hobby_stages WHERE hobby_stage_id=?", stageId);
      jdbc.update("DELETE FROM hobbies WHERE hobby_id=?", hobbyId);
    }
    super.cleanupSeededHobbies(userId);
  }

  HttpResponse<String> save(String token, String id) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/activities/" + id + "/save"))
            .timeout(java.time.Duration.ofSeconds(10))
            .PUT(HttpRequest.BodyPublishers.noBody());
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  String activeToken() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return signedIn.path("accessToken").asText();
  }

  @Test
  void savesPublishedActivityWithoutPlantingAndPreservesOriginalTimestamp() throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    var first = save(token, Long.toString(id));
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(mapper.readTree(first.body()).path("code").asText()).isEqualTo("SAVED200_1");
    var body = result(first);
    assertThat(body.path("activityId").asLong()).isEqualTo(id);
    assertThat(body.path("isSaved").asBoolean()).isTrue();
    assertThat(body.path("savedAt").asText()).isNotBlank();
    var again = save(token, Long.toString(id));
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(result(again)).isEqualTo(body);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_activities WHERE activity_id=?", Integer.class, id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM user_hobbies WHERE user_id=?",
                Integer.class,
                createdUsers.iterator().next()))
        .isZero();
  }

  @Test
  void concurrentSavesReturnOneOriginalTimestamp() throws Exception {
    String token = activeToken();
    String id = Long.toString(activity("PUBLISHED"));
    try (var executor = Executors.newFixedThreadPool(4)) {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<HttpResponse<String>>>();
      for (int i = 0; i < 4; i++)
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return save(token, id);
                }));
      start.countDown();
      var timestamps = new HashSet<String>();
      for (var f : futures) {
        var response = f.get(15, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        timestamps.add(result(response).path("savedAt").asText());
      }
      assertThat(timestamps).hasSize(1);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_activities WHERE activity_id=?",
                Integer.class,
                Long.valueOf(id)))
        .isEqualTo(1);
  }

  @Test
  void savesAreIndependentForDifferentUsers() throws Exception {
    String first = activeToken();
    String id = Long.toString(activity("PUBLISHED"));
    assertThat(save(first, id).statusCode()).isEqualTo(200);
    var second =
        result(login("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}"));
    long otherId = second.path("userId").asLong();
    createdUsers.add(otherId);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", otherId);
    assertThat(save(second.path("accessToken").asText(), id).statusCode()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_activities WHERE activity_id=?",
                Integer.class,
                Long.valueOf(id)))
        .isEqualTo(2);
  }

  @Test
  void rejectsMissingNonPublishedAndUnpublishedSavedActivities() throws Exception {
    String token = activeToken();
    for (String state : List.of("DRAFT", "REVIEW", "REJECTED")) {
      var response = save(token, Long.toString(activity(state)));
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("ACTIVITY404_1");
    }
    assertThat(save(token, "9223372036854775807").statusCode()).isEqualTo(404);
    long id = activity("PUBLISHED");
    assertThat(save(token, Long.toString(id)).statusCode()).isEqualTo(200);
    jdbc.update("UPDATE activities SET review_status='DRAFT' WHERE activity_id=?", id);
    assertThat(save(token, Long.toString(id)).statusCode()).isEqualTo(404);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_activities WHERE activity_id=?", Integer.class, id))
        .isEqualTo(1);
  }

  @Test
  void validatesPathAndRequiresActiveAuthenticatedAccount() throws Exception {
    var signedIn = result(login());
    long userId = signedIn.path("userId").asLong();
    createdUsers.add(userId);
    String token = signedIn.path("accessToken").asText();
    String id = Long.toString(activity("PUBLISHED"));
    assertThat(save(null, id).statusCode()).isEqualTo(401);
    assertThat(save(token, id).statusCode()).isEqualTo(403);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", userId);
    for (String invalid : List.of("0", "-1", "abc", "9223372036854775808"))
      assertThat(save(token, invalid).statusCode()).isEqualTo(400);
    jdbc.update("UPDATE users SET account_status='SUSPENDED' WHERE user_id=?", userId);
    assertThat(save(token, id).statusCode()).isEqualTo(403);
    jdbc.update(
        "UPDATE users SET account_status='WITHDRAWN', withdrawn_at=now(),restorable_until=now()+interval '30 days',status_before_withdrawal='ACTIVE' WHERE user_id=?",
        userId);
    assertThat(save(token, id).statusCode()).isEqualTo(403);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_activities WHERE activity_id=?",
                Integer.class,
                Long.valueOf(id)))
        .isZero();
  }

  HttpResponse<String> unsave(String token, String id) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/activities/" + id + "/save"))
            .timeout(java.time.Duration.ofSeconds(10))
            .DELETE();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void unsaveRemovesOnlyOwnBookmarkAndRepeatedRequestsSucceed() throws Exception {
    String token = activeToken();
    long owner = createdUsers.iterator().next();
    String id = Long.toString(activity("PUBLISHED"));
    assertThat(save(token, id).statusCode()).isEqualTo(200);
    var other =
        result(login("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}"));
    long otherId = other.path("userId").asLong();
    createdUsers.add(otherId);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", otherId);
    assertThat(save(other.path("accessToken").asText(), id).statusCode()).isEqualTo(200);
    for (int i = 0; i < 2; i++) {
      var response = unsave(token, id);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
      assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("SAVED200_2");
      assertThat(result(response).path("activityId").asLong()).isEqualTo(Long.valueOf(id));
      assertThat(result(response).path("isSaved").isBoolean()).isTrue();
      assertThat(result(response).path("isSaved").asBoolean()).isFalse();
      assertThat(result(response).has("savedAt")).isFalse();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_activities WHERE user_id=?", Integer.class, owner))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_activities WHERE user_id=?", Integer.class, otherId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activities WHERE activity_id=?",
                Integer.class,
                Long.valueOf(id)))
        .isEqualTo(1);
  }

  @Test
  void unsaveAcceptsUnpublishedDeletedAndNeverSavedActivities() throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    assertThat(save(token, Long.toString(id)).statusCode()).isEqualTo(200);
    jdbc.update("UPDATE activities SET review_status='DRAFT' WHERE activity_id=?", id);
    assertThat(unsave(token, Long.toString(id)).statusCode()).isEqualTo(200);
    jdbc.update("DELETE FROM activities WHERE activity_id=?", id);
    assertThat(unsave(token, Long.toString(id)).statusCode()).isEqualTo(200);
    assertThat(unsave(token, "9223372036854775807").statusCode()).isEqualTo(200);
    assertThat(unsave(token, Long.toString(activity("DRAFT"))).statusCode()).isEqualTo(200);
  }

  @Test
  void concurrentSaveAndUnsaveRequestsAreSuccessfulAndRemainUsable() throws Exception {
    String token = activeToken();
    String id = Long.toString(activity("PUBLISHED"));
    try (var executor = Executors.newFixedThreadPool(4)) {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<HttpResponse<String>>>();
      for (int i = 0; i < 4; i++) {
        boolean adding = i % 2 == 0;
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return adding ? save(token, id) : unsave(token, id);
                }));
      }
      start.countDown();
      for (var f : futures) assertThat(f.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }
    assertThat(unsave(token, id).statusCode()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_activities WHERE activity_id=?",
                Integer.class,
                Long.valueOf(id)))
        .isZero();
    assertThat(save(token, id).statusCode()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_activities WHERE activity_id=?",
                Integer.class,
                Long.valueOf(id)))
        .isEqualTo(1);
  }

  @Test
  void unsaveValidatesIdAndRejectsUnauthorizedAccountsWithoutDeleting() throws Exception {
    var signedIn = result(login());
    long userId = signedIn.path("userId").asLong();
    createdUsers.add(userId);
    String token = signedIn.path("accessToken").asText();
    String id = Long.toString(activity("PUBLISHED"));
    assertThat(unsave(null, id).statusCode()).isEqualTo(401);
    assertThat(unsave(token, id).statusCode()).isEqualTo(403);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", userId);
    assertThat(save(token, id).statusCode()).isEqualTo(200);
    for (String invalid : List.of("0", "-1", "abc", "9223372036854775808"))
      assertThat(unsave(token, invalid).statusCode()).isEqualTo(400);
    jdbc.update("UPDATE users SET account_status='SUSPENDED' WHERE user_id=?", userId);
    assertThat(unsave(token, id).statusCode()).isEqualTo(403);
    jdbc.update(
        "UPDATE users SET account_status='WITHDRAWN',withdrawn_at=now(),restorable_until=now()+interval '30 days',status_before_withdrawal='ACTIVE' WHERE user_id=?",
        userId);
    assertThat(unsave(token, id).statusCode()).isEqualTo(403);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_activities WHERE user_id=?", Integer.class, userId))
        .isEqualTo(1);
  }
}
