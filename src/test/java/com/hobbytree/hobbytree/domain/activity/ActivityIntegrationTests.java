package com.hobbytree.hobbytree.domain.activity;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.activity.service.SavedActivityService;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class ActivityIntegrationTests extends IntegrationTestSupport {
  private static final String ANONYMIZE =
      """
      UPDATE users SET account_status='ANONYMIZED', provider=NULL, provider_user_id=NULL,
        anonymized_at=now(), nickname=NULL, profile_image_key=NULL, age_range=NULL,
        withdrawn_at=NULL, restorable_until=NULL, status_before_withdrawal=NULL
      WHERE user_id=?
      """;
  @Autowired private SavedActivityService savedActivityService;
  Long hobbyId;
  Long stageId;

  @ParameterizedTest
  @ValueSource(strings = {"save", "unsave", "list"})
  void anonymizedMemberCannotUseTheServiceOrHttpAndBookmarksArePreserved(String operation)
      throws Exception {
    String token = activeToken();
    long userId = createdUsers.iterator().next();
    long original = activity("PUBLISHED");
    assertThat(save(token, Long.toString(original)).statusCode()).isEqualTo(200);
    long candidate = activity("PUBLISHED");
    var before = jdbc.queryForList("SELECT * FROM saved_activities WHERE user_id=?", userId);
    jdbc.update(ANONYMIZE, userId);

    assertThatThrownBy(
            () -> {
              switch (operation) {
                case "save" -> savedActivityService.save(userId, candidate);
                case "unsave" -> savedActivityService.unsave(userId, original);
                case "list" -> savedActivityService.listMine(userId, null, null, null);
                default -> throw new AssertionError(operation);
              }
            })
        .isInstanceOfSatisfying(
            AuthException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.ACCESS_TOKEN));
    var response =
        switch (operation) {
          case "save" -> save(token, Long.toString(candidate));
          case "unsave" -> unsave(token, Long.toString(original));
          case "list" -> get(token, "/api/v1/users/me/saved-activities");
          default -> throw new AssertionError(operation);
        };
    assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH401_2");
    assertThat(jdbc.queryForList("SELECT * FROM saved_activities WHERE user_id=?", userId))
        .isEqualTo(before);
  }

  @ParameterizedTest
  @CsvSource({"save,true", "save,false", "unsave,true", "unsave,false"})
  void waitingSaveOrUnsaveChecksAnonymizationAfterTheUserWriterCommitsOrRollsBack(
      String operation, boolean commit) throws Exception {
    String token = activeToken();
    long userId = createdUsers.iterator().next();
    long original = activity("PUBLISHED");
    assertThat(save(token, Long.toString(original)).statusCode()).isEqualTo(200);
    long candidate = activity("PUBLISHED");
    var before = jdbc.queryForList("SELECT * FROM saved_activities WHERE user_id=?", userId);
    try (var executor = Executors.newSingleThreadExecutor();
        var writer = dataSource.getConnection()) {
      writer.setAutoCommit(false);
      try {
        int writerPid = backendPid(writer);
        try (var statement = writer.prepareStatement(ANONYMIZE)) {
          statement.setLong(1, userId);
          statement.executeUpdate();
        }
        var pending =
            executor.submit(
                () ->
                    operation.equals("save")
                        ? save(token, Long.toString(candidate))
                        : unsave(token, Long.toString(original)));
        awaitBlockedRequest(writerPid, writerPid, pending);
        if (commit) writer.commit();
        else writer.rollback();
        var response = pending.get(15, TimeUnit.SECONDS);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(commit ? 401 : 200);
        if (commit) {
          assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH401_2");
          assertThat(jdbc.queryForList("SELECT * FROM saved_activities WHERE user_id=?", userId))
              .isEqualTo(before);
        } else {
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM saved_activities WHERE user_id=?",
                      Integer.class,
                      userId))
              .isEqualTo(operation.equals("save") ? 2 : 0);
        }
      } finally {
        writer.rollback();
      }
    }
  }

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
  void rejectsPublishedActivityInUnsupportedHobbyWithoutCreatingABookmark() throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    jdbc.update("UPDATE hobbies SET is_supported=false WHERE hobby_id=?", hobbyId);
    var before = jdbc.queryForMap("SELECT * FROM activities WHERE activity_id=?", id);
    assertNotSavable(save(token, Long.toString(id)));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_activities WHERE activity_id=?", Integer.class, id))
        .isZero();
    assertThat(jdbc.queryForMap("SELECT * FROM activities WHERE activity_id=?", id))
        .isEqualTo(before);
    assertThat(get(token, "/api/v1/activities/" + id).statusCode()).isEqualTo(404);
    assertThat(get(token, "/api/v1/hobby-stages/" + stageId + "/activities").statusCode())
        .isEqualTo(404);
  }

  @Test
  void rejectedRepeatSavePreservesUnavailableBookmarkAndRestoredHobbyKeepsSavedAt()
      throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    var first = save(token, Long.toString(id));
    assertThat(first.statusCode()).isEqualTo(200);
    var before = jdbc.queryForList("SELECT * FROM saved_activities WHERE activity_id=?", id);
    jdbc.update("UPDATE hobbies SET is_supported=false WHERE hobby_id=?", hobbyId);
    assertNotSavable(save(token, Long.toString(id)));
    assertThat(jdbc.queryForList("SELECT * FROM saved_activities WHERE activity_id=?", id))
        .isEqualTo(before);
    var list = get(token, "/api/v1/users/me/saved-activities");
    assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
    var items = result(list).path("items");
    assertThat(items.size()).isEqualTo(1);
    assertThat(items.get(0).propertyNames())
        .containsExactlyInAnyOrder("activityId", "savedAt", "isAvailable", "title");
    assertThat(items.get(0).path("isAvailable").asBoolean()).isFalse();
    assertThat(items.get(0).path("savedAt")).isEqualTo(result(first).path("savedAt"));
    jdbc.update("UPDATE hobbies SET is_supported=true WHERE hobby_id=?", hobbyId);
    var restored = save(token, Long.toString(id));
    assertThat(restored.statusCode()).isEqualTo(200);
    assertThat(result(restored)).isEqualTo(result(first));
    jdbc.update("UPDATE hobbies SET is_supported=false WHERE hobby_id=?", hobbyId);
    assertThat(unsave(token, Long.toString(id)).statusCode()).isEqualTo(200);
    assertThat(unsave(token, Long.toString(id)).statusCode()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_activities WHERE activity_id=?", Integer.class, id))
        .isZero();
  }

  @Test
  void usesTheCurrentStageParentWhenAnActivityMovesToAnUnsupportedHobby() throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    long unsupported =
        jdbc.queryForObject(
            "INSERT INTO hobbies(hobby_code,hobby_name,is_supported) VALUES (?,'TEST ONLY unsupported',false) RETURNING hobby_id",
            Long.class,
            "save-u-" + UUID.randomUUID());
    try {
      jdbc.update(
          "UPDATE hobby_stages SET hobby_id=? WHERE hobby_stage_id=?", unsupported, stageId);
      assertNotSavable(save(token, Long.toString(id)));
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM saved_activities WHERE activity_id=?", Integer.class, id))
          .isZero();
    } finally {
      jdbc.update("UPDATE hobby_stages SET hobby_id=? WHERE hobby_stage_id=?", hobbyId, stageId);
      jdbc.update("DELETE FROM hobbies WHERE hobby_id=?", unsupported);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void waitingSaveChecksSupportAfterTheCatalogWriterCommitsOrRollsBack(boolean commit)
      throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    try (var executor = Executors.newSingleThreadExecutor();
        var writer = dataSource.getConnection()) {
      writer.setAutoCommit(false);
      try {
        int writerPid = backendPid(writer);
        try (var statement =
            writer.prepareStatement("UPDATE hobbies SET is_supported=false WHERE hobby_id=?")) {
          statement.setLong(1, hobbyId);
          statement.executeUpdate();
        }
        var request = executor.submit(() -> save(token, Long.toString(id)));
        awaitBlockedRequest(writerPid, writerPid, request);
        if (commit) writer.commit();
        else writer.rollback();
        var response = request.get(15, TimeUnit.SECONDS);
        if (commit) assertNotSavable(response);
        else {
          assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
          assertThat(result(save(token, Long.toString(id)))).isEqualTo(result(response));
        }
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM saved_activities WHERE activity_id=?", Integer.class, id))
            .isEqualTo(commit ? 0 : 1);
      } finally {
        writer.rollback();
      }
    }
  }

  @Test
  void saveCanFinishBeforeAnOverlappingSupportChangeBlockedByActivityReaders() throws Exception {
    String token = activeToken();
    long id = activity("PUBLISHED");
    try (var executor = Executors.newSingleThreadExecutor();
        var reader = dataSource.getConnection()) {
      reader.setAutoCommit(false);
      try {
        int readerPid = backendPid(reader);
        try (var statement =
            reader.prepareStatement(
                "SELECT activity_id FROM activities WHERE activity_id=? FOR SHARE")) {
          statement.setLong(1, id);
          try (var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
          }
        }
        var edit =
            executor.submit(
                () ->
                    jdbc.update("UPDATE hobbies SET is_supported=false WHERE hobby_id=?", hobbyId));
        awaitBlockedRequest(readerPid, readerPid, edit);
        var saved = save(token, Long.toString(id));
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        reader.commit();
        assertThat(edit.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        assertNotSavable(save(token, Long.toString(id)));
        var list = get(token, "/api/v1/users/me/saved-activities");
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(result(list).path("items").get(0).path("isAvailable").asBoolean()).isFalse();
      } finally {
        reader.rollback();
      }
    }
  }

  private int backendPid(java.sql.Connection connection) throws Exception {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
      assertThat(rows.next()).isTrue();
      return rows.getInt(1);
    }
  }

  private void assertNotSavable(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
    var body = mapper.readTree(response.body());
    assertThat(body.path("isSuccess").asBoolean()).isFalse();
    assertThat(body.path("code").asText()).isEqualTo("ACTIVITY404_1");
    assertThat(body.path("message").asText()).isEqualTo("활동이 없거나 저장할 수 없음");
    assertThat(body.path("result").isNull()).isTrue();
  }

  private HttpResponse<String> get(String token, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(java.time.Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
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

  /** Checks that an existing bookmark remains visible in API-33 after its activity is hidden. */
  @Test
  void rejectsInvalidSavesAndKeepsHiddenBookmarkVisibleInList() throws Exception {
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
    var listRequest =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/users/me/saved-activities"))
            .timeout(java.time.Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    var listResponse = client.send(listRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(listResponse.statusCode()).as(listResponse.body()).isEqualTo(200);
    var items = result(listResponse).path("items");
    assertThat(items.size()).isEqualTo(1);
    var hidden = items.get(0);
    assertThat(hidden.propertyNames())
        .containsExactlyInAnyOrder("activityId", "savedAt", "isAvailable", "title");
    assertThat(hidden.path("activityId").asLong()).isEqualTo(id);
    assertThat(hidden.path("isAvailable").asBoolean()).isFalse();
    assertThat(hidden.path("title").asText()).isEqualTo("이용할 수 없는 활동입니다");
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
  void saveThenUnsaveLeavesNoBookmarkImmediatelyAfterConcurrentRequests() throws Exception {
    assertOrderedConcurrentSaveAndUnsave(true);
  }

  @Test
  void unsaveThenSaveLeavesBookmarkImmediatelyAfterConcurrentRequests() throws Exception {
    assertOrderedConcurrentSaveAndUnsave(false);
  }

  void assertOrderedConcurrentSaveAndUnsave(boolean saveFirst) throws Exception {
    String token = activeToken();
    long userId = createdUsers.iterator().next();
    long activityId = activity("PUBLISHED");
    String id = Long.toString(activityId);
    if (!saveFirst) assertThat(save(token, id).statusCode()).isEqualTo(200);

    try (var executor = Executors.newFixedThreadPool(2);
        var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        int holderPid;
        try (var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
          rows.next();
          holderPid = rows.getInt(1);
        }
        try (var statement =
            connection.prepareStatement("SELECT user_id FROM users WHERE user_id=? FOR UPDATE")) {
          statement.setLong(1, userId);
          try (var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
          }
        }
        // Queue the first request behind our row lock before starting the second request.
        var first = executor.submit(() -> saveFirst ? save(token, id) : unsave(token, id));
        int firstPid = awaitBlockedRequest(holderPid, holderPid, first);
        var second = executor.submit(() -> saveFirst ? unsave(token, id) : save(token, id));
        awaitBlockedRequest(holderPid, firstPid, second);
        connection.commit();
        var firstResponse = first.get(15, TimeUnit.SECONDS);
        var secondResponse = second.get(15, TimeUnit.SECONDS);
        assertThat(firstResponse.statusCode()).isEqualTo(200);
        assertThat(secondResponse.statusCode()).isEqualTo(200);
        // Inspect the concurrent outcome before any follow-up mutation can overwrite it.
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM saved_activities WHERE user_id=? AND activity_id=?",
                    Integer.class,
                    userId,
                    activityId))
            .isEqualTo(saveFirst ? 0 : 1);
        if (!saveFirst) {
          var savedAt =
              jdbc.queryForObject(
                  "SELECT saved_at FROM saved_activities WHERE user_id=? AND activity_id=?",
                  java.sql.Timestamp.class,
                  userId,
                  activityId);
          assertThat(savedAt.toInstant())
              .isEqualTo(java.time.Instant.parse(result(secondResponse).path("savedAt").asText()));
        }
      } finally {
        connection.rollback();
      }
    }
  }

  int awaitBlockedRequest(int holderPid, int previousPid, Future<?> request) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      assertThat(request.isDone())
          .as("request must wait for the expected PostgreSQL lock")
          .isFalse();
      var waiting =
          jdbc.queryForList(
              "SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND pid<>?"
                  + " AND (?=ANY(pg_blocking_pids(pid)) OR ?=ANY(pg_blocking_pids(pid)))",
              Integer.class,
              previousPid,
              holderPid,
              previousPid);
      if (!waiting.isEmpty()) return waiting.getFirst();
      Thread.sleep(20);
    }
    throw new AssertionError("Request did not queue behind the expected PostgreSQL lock");
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
