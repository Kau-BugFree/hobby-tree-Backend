package com.hobbytree.hobbytree.domain.tree;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class PlantUserHobbyIntegrationTests extends IntegrationTestSupport {
  private static final String PATH = "/api/v1/user-hobbies";
  @MockitoSpyBean private UserHobbyRepository trees;

  @Test
  void plantsDirectlyAtStageZeroAndImmediatelyAppearsInTheList() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id());
    var response = plant(owner.token(), hobbyId, null);
    var tree = assertCreated(response);

    assertThat(tree.propertyNames())
        .containsExactlyInAnyOrder(
            "userHobbyId",
            "hobbyId",
            "hobbyName",
            "treeStatus",
            "currentStageNumber",
            "distinctCompletedActivityCount",
            "plantedAt");
    assertThat(tree.path("hobbyId").asLong()).isEqualTo(hobbyId);
    assertThat(tree.path("hobbyName").asText()).isEqualTo("심기 테스트");
    assertThat(tree.path("currentStageNumber").asInt()).isZero();
    assertThat(tree.path("treeStatus").asText()).isEqualTo("ACTIVE");
    assertThat(tree.path("distinctCompletedActivityCount").asLong()).isZero();
    assertThat(tree.path("plantedAt").asText()).isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "SELECT user_id FROM user_hobbies WHERE user_hobby_id=?",
                Long.class,
                tree.path("userHobbyId").asLong()))
        .isEqualTo(owner.id());

    var page = list(owner.token());
    assertThat(page.path("items").size()).isEqualTo(1);
    assertThat(page.path("occupiedTreeCount").asLong()).isEqualTo(1);
    var listed = page.path("items").get(0);
    assertThat(listed.path("userHobbyId")).isEqualTo(tree.path("userHobbyId"));
    assertThat(listed.path("currentStageNumber").asInt()).isZero();
    assertThat(listed.path("currentStageName").asText()).isEqualTo("씨앗");
    assertThat(listed.path("plantedAt")).isEqualTo(tree.path("plantedAt"));
  }

  @Test
  void plantsUsingAnOwnedRecommendationContainingTheHobby() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id());
    long recommendationId = recommendation(owner.id(), hobbyId);

    assertCreated(plant(owner.token(), hobbyId, recommendationId));
  }

  @Test
  void rejectsAnotherMembersRecommendation() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id());
    long other = otherUser();
    long recommendationId = recommendation(other, hobbyId);

    assertError(plant(owner.token(), hobbyId, recommendationId), 404, "RECOMMENDATION404_1");
    assertThat(treeCount(owner.id())).isZero();
  }

  @Test
  void rejectsAnOwnedRecommendationForADifferentHobby() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id());
    long recommendationId = recommendation(owner.id(), hobby(owner.id()));

    assertError(plant(owner.token(), hobbyId, recommendationId), 404, "RECOMMENDATION404_1");
    assertThat(treeCount(owner.id())).isZero();
  }

  @Test
  void rejectsAMissingRecommendation() throws Exception {
    var owner = signIn();
    assertError(
        plant(owner.token(), hobby(owner.id()), Long.MAX_VALUE), 404, "RECOMMENDATION404_1");
    assertThat(treeCount(owner.id())).isZero();
  }

  @Test
  void rejectsMissingAndUnsupportedHobbies() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id());
    jdbc.update("UPDATE hobbies SET is_supported=false WHERE hobby_id=?", hobbyId);

    assertError(plant(owner.token(), Long.MAX_VALUE, null), 404, "HOBBY404_1");
    assertError(plant(owner.token(), hobbyId, null), 404, "HOBBY404_1");
    assertThat(treeCount(owner.id())).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "PAUSED", "ENDED"})
  void rejectsAnExistingTreeRegardlessOfItsStatus(String status) throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id());
    seedTree(owner.id(), hobbyId, status);

    assertError(plant(owner.token(), hobbyId, null), 409, "TREE409_1");
    assertThat(treeCount(owner.id())).isEqualTo(1);
  }

  @Test
  void countsPausedTreesAgainstTheFiveSlotLimit() throws Exception {
    var owner = signIn();
    seedOccupied(owner.id(), 5);

    assertError(plant(owner.token(), hobby(owner.id()), null), 409, "TREE409_2");
    assertThat(treeCount(owner.id())).isEqualTo(5);
  }

  @Test
  void endedTreesReleaseSlotsButKeepTheirOriginalRows() throws Exception {
    var owner = signIn();
    seedOccupied(owner.id(), 4);
    long endedHobby = hobby(owner.id());
    seedTree(owner.id(), endedHobby, "ENDED");

    assertCreated(plant(owner.token(), hobby(owner.id()), null));
    assertThat(treeCount(owner.id())).isEqualTo(6);
    assertThat(list(owner.token()).path("occupiedTreeCount").asLong()).isEqualTo(5);
  }

  @Test
  void mapsTheRealDatabaseUniqueViolationAndRollsBackTheFailedInsert() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id());
    seedTree(owner.id(), hobbyId, "ACTIVE");
    // Force the final DB safeguard, independently of the normal duplicate precheck.
    doReturn(false).when(trees).existsByUser_UserIdAndHobby_HobbyId(owner.id(), hobbyId);

    assertError(plant(owner.token(), hobbyId, null), 409, "TREE409_1");
    assertThat(treeCount(owner.id())).isEqualTo(1);
    assertCreated(plant(owner.token(), hobby(owner.id()), null));
  }

  @Test
  void serializesConcurrentRequestsForTheSameHobbyOnSeparateConnections() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id());

    var responses =
        competeUnderUserLock(
            owner.id(),
            () -> plant(owner.token(), hobbyId, null),
            () -> plant(owner.token(), hobbyId, null));

    assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
    assertThat(responses.stream().filter(r -> r.statusCode() == 409).map(this::code))
        .containsExactly("TREE409_1");
    assertThat(treeCount(owner.id())).isEqualTo(1);
  }

  @Test
  void permitsOnlyOneConcurrentPlantWhenFourSlotsAreAlreadyOccupied() throws Exception {
    var owner = signIn();
    seedOccupied(owner.id(), 4);
    long first = hobby(owner.id());
    long second = hobby(owner.id());

    var responses =
        competeUnderUserLock(
            owner.id(),
            () -> plant(owner.token(), first, null),
            () -> plant(owner.token(), second, null));

    assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
    assertThat(responses.stream().filter(r -> r.statusCode() == 409).map(this::code))
        .containsExactly("TREE409_2");
    assertThat(treeCount(owner.id())).isEqualTo(5);
    assertThat(list(owner.token()).path("occupiedTreeCount").asLong()).isEqualTo(5);
  }

  @Test
  void differentMembersCanPlantTheSameHobbyWhileItsSharedLockIsHeld() throws Exception {
    var first = signIn();
    socialId = "plant-second-" + UUID.randomUUID();
    when(verifier.verify(any(), anyString())).thenReturn(socialId);
    var second = signIn();
    assertThat(second.id()).isNotEqualTo(first.id());
    long hobbyId = hobby(first.id());
    try (var executor = Executors.newFixedThreadPool(2);
        var shared = dataSource.getConnection()) {
      shared.setAutoCommit(false);
      try {
        try (var statement =
            shared.prepareStatement("SELECT hobby_id FROM hobbies WHERE hobby_id=? FOR SHARE")) {
          statement.setLong(1, hobbyId);
          statement.executeQuery().close();
        }
        var left = executor.submit(() -> plant(first.token(), hobbyId, null));
        var right = executor.submit(() -> plant(second.token(), hobbyId, null));
        assertCreated(left.get(15, TimeUnit.SECONDS));
        assertCreated(right.get(15, TimeUnit.SECONDS));
      } finally {
        shared.rollback();
      }
    }
    assertThat(treeCount(first.id())).isEqualTo(1);
    assertThat(treeCount(second.id())).isEqualTo(1);
  }

  @Test
  void rechecksAccountStatusAfterAcquiringTheUserLock() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id());
    try (var executor = Executors.newSingleThreadExecutor();
        var lock = lockUser(owner.id())) {
      Future<HttpResponse<String>> pending =
          executor.submit(() -> plant(owner.token(), hobbyId, null));
      try {
        awaitBlockedRequests(1);
        try (var change =
            lock.prepareStatement("UPDATE users SET account_status='SUSPENDED' WHERE user_id=?")) {
          change.setLong(1, owner.id());
          change.executeUpdate();
        }
        lock.commit();
        assertError(pending.get(15, TimeUnit.SECONDS), 403, "AUTH403_1");
        assertThat(treeCount(owner.id())).isZero();
      } finally {
        lock.rollback();
      }
    }
  }

  @Test
  void requiresAValidCurrentJwt() throws Exception {
    var owner = signIn();
    long hobbyId = hobby(owner.id());
    assertError(plant(null, hobbyId, null), 401, "AUTH401_2");
    assertError(plant("not-a-jwt", hobbyId, null), 401, "AUTH401_2");
    redis.delete("auth:session:" + owner.id());
    assertError(plant(owner.token(), hobbyId, null), 401, "AUTH401_2");
    assertThat(treeCount(owner.id())).isZero();
  }

  @Test
  void rejectsPendingOnboarding() throws Exception {
    var signedIn = result(login());
    long userId = signedIn.path("userId").asLong();
    assertError(
        plant(signedIn.path("accessToken").asText(), hobby(userId), null),
        403,
        AuthErrorCode.ONBOARDING_INCOMPLETE.getCode());
    assertThat(treeCount(userId)).isZero();
  }

  @ParameterizedTest
  @CsvSource({"SUSPENDED, AUTH403_1", "WITHDRAWN, AUTH403_3"})
  void rejectsIneligibleMembers(String status, String expectedCode) throws Exception {
    var owner = signIn();
    jdbc.update(
        """
        UPDATE users SET account_status=?,
          withdrawn_at=CASE WHEN ?='WITHDRAWN' THEN now() END,
          restorable_until=CASE WHEN ?='WITHDRAWN' THEN now()+INTERVAL '720 hours' END,
          status_before_withdrawal=CASE WHEN ?='WITHDRAWN' THEN 'ACTIVE' END
        WHERE user_id=?
        """,
        status,
        status,
        status,
        status,
        owner.id());
    assertError(plant(owner.token(), hobby(owner.id()), null), 403, expectedCode);
    assertThat(treeCount(owner.id())).isZero();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      nullValues = "-",
      value = {
        "{}|hobbyId",
        "{\"hobbyId\":null}|hobbyId",
        "{\"hobbyId\":0}|hobbyId",
        "{\"hobbyId\":-1}|hobbyId",
        "{\"hobbyId\":\"invalid\"}|-",
        "{\"hobbyId\":1,\"recommendationId\":0}|recommendationId",
        "{\"hobbyId\":1,\"recommendationId\":-1}|recommendationId",
        "{broken|-"
      })
  void rejectsInvalidRequestBodies(String body, String invalidField) throws Exception {
    var owner = signIn();
    var response = post(owner.token(), body);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(code(response)).isEqualTo("COMMON400_1");
    if (invalidField == null) {
      assertThat(result(response).isNull()).isTrue();
    } else {
      assertThat(result(response).propertyNames()).containsExactly(invalidField);
      assertThat(result(response).path(invalidField).asText()).isNotBlank();
    }
    assertThat(treeCount(owner.id())).isZero();
  }

  private SignedInUser signIn() throws Exception {
    var response = login();
    assertThat(response.statusCode()).isEqualTo(200);
    var signedIn = result(response);
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return new SignedInUser(id, signedIn.path("accessToken").asText());
  }

  private long otherUser() {
    long id =
        jdbc.queryForObject(
            "INSERT INTO users(provider,provider_user_id,account_status) VALUES ('KAKAO',?,'ACTIVE') RETURNING user_id",
            Long.class,
            UUID.randomUUID().toString());
    createdUsers.add(id);
    return id;
  }

  private long hobby(long owner) {
    long id =
        jdbc.queryForObject(
            "INSERT INTO hobbies(hobby_code,hobby_name) VALUES (?,'심기 테스트') RETURNING hobby_id",
            Long.class,
            "profile-test-" + owner + "-" + UUID.randomUUID().toString().substring(0, 12));
    jdbc.update("INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,0,'씨앗')", id);
    return id;
  }

  private void seedTree(long userId, long hobbyId, String status) {
    jdbc.update(
        "INSERT INTO user_hobbies(user_id,hobby_id,tree_status) VALUES (?,?,?)",
        userId,
        hobbyId,
        status);
  }

  private void seedOccupied(long userId, int count) {
    for (int i = 0; i < count; i++)
      seedTree(userId, hobby(userId), i % 2 == 0 ? "ACTIVE" : "PAUSED");
  }

  private long recommendation(long userId, long hobbyId) {
    long id =
        jdbc.queryForObject(
            "INSERT INTO recommendations(user_id,preference_snapshot) VALUES (?,'{}'::jsonb) RETURNING recommendation_id",
            Long.class,
            userId);
    jdbc.update(
        "INSERT INTO recommendation_items(recommendation_id,hobby_id,rank_order,recommendation_reason) VALUES (?,?,1,'테스트 추천')",
        id,
        hobbyId);
    return id;
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update(
        """
        DELETE FROM user_hobbies WHERE hobby_id IN
          (SELECT hobby_id FROM hobbies WHERE hobby_code LIKE ?)
        """,
        "profile-test-" + userId + "-%");
    jdbc.update(
        """
        DELETE FROM recommendation_items WHERE hobby_id IN
          (SELECT hobby_id FROM hobbies WHERE hobby_code LIKE ?)
        """,
        "profile-test-" + userId + "-%");
    jdbc.update("DELETE FROM recommendations WHERE user_id=?", userId);
    super.cleanupSeededHobbies(userId);
  }

  private HttpResponse<String> plant(String token, long hobbyId, Long recommendationId)
      throws Exception {
    String body =
        "{\"hobbyId\":"
            + hobbyId
            + (recommendationId == null ? "" : ",\"recommendationId\":" + recommendationId)
            + "}";
    return post(token, body);
  }

  private HttpResponse<String> post(String token, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode list(String token) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("TREE200_1");
    return result(response);
  }

  private JsonNode assertCreated(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    assertThat(code(response)).isEqualTo("TREE201_1");
    assertThat(mapper.readTree(response.body()).path("message").asText())
        .isEqualTo("취미 나무가 심어졌습니다.");
    return result(response);
  }

  private void assertError(HttpResponse<String> response, int status, String expectedCode) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    assertThat(code(response)).isEqualTo(expectedCode);
    assertThat(result(response).isNull()).isTrue();
  }

  private String code(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("code").asText();
  }

  private long treeCount(long userId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM user_hobbies WHERE user_id=?", Long.class, userId);
  }

  private Connection lockUser(long userId) throws Exception {
    var connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (var statement =
        connection.prepareStatement("SELECT user_id FROM users WHERE user_id=? FOR UPDATE")) {
      statement.setLong(1, userId);
      statement.executeQuery().close();
    }
    return connection;
  }

  private List<HttpResponse<String>> competeUnderUserLock(
      long userId, Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second)
      throws Exception {
    try (var executor = Executors.newFixedThreadPool(2);
        var lock = lockUser(userId)) {
      var left = executor.submit(first);
      var right = executor.submit(second);
      try {
        awaitBlockedRequests(2);
        assertThat(left.isDone()).isFalse();
        assertThat(right.isDone()).isFalse();
      } finally {
        lock.rollback();
      }
      return List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
    }
  }

  private void awaitBlockedRequests(int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    do {
      var pids =
          jdbc.queryForList(
              """
          SELECT pid FROM pg_stat_activity
          WHERE datname=current_database() AND pid<>pg_backend_pid()
            AND wait_event_type='Lock'
            AND query ILIKE '%from users%' AND query ILIKE '%for%update%'
          """,
              Long.class);
      if (pids.size() >= expected) {
        assertThat(pids).doesNotHaveDuplicates();
        return;
      }
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);
    fail("Expected " + expected + " plant transactions waiting on separate PostgreSQL connections");
  }

  private record SignedInUser(long id, String token) {}
}
