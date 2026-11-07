package com.hobbytree.hobbytree.domain.tree;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.auth.service.JwtTokenService;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class UserHobbyStatusIntegrationTests extends IntegrationTestSupport {
  private static final String PATH = "/api/v1/user-hobbies";
  private static final String PAUSED_AT = "2026-11-01T01:00:00Z";
  private static final String ENDED_AT = "2026-11-02T01:00:00Z";
  @Autowired private UserHobbyRepository trees;

  @ParameterizedTest
  @CsvSource({"ACTIVE,PAUSED", "ACTIVE,ENDED", "PAUSED,ACTIVE", "PAUSED,ENDED", "ENDED,ACTIVE"})
  void appliesEveryAllowedTransitionAndPreservesGrowthAndPlantedAt(String from, String to)
      throws Exception {
    var owner = signIn();
    long id = tree(owner.id(), from);
    var before = row(id);
    Instant requestedAt = Instant.now();

    var response = patch(owner.token(), id, to);
    var body = success(response);
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder(
            "userHobbyId", "treeStatus", "currentStageNumber", "pausedAt", "endedAt", "updatedAt");
    assertThat(body.path("userHobbyId").asLong()).isEqualTo(id);
    assertThat(body.path("treeStatus").asText()).isEqualTo(to);
    assertThat(body.path("currentStageNumber").asInt()).isEqualTo(4);
    assertThat(row(id).get("planted_at")).isEqualTo(before.get("planted_at"));
    assertThat(row(id).get("current_stage_number")).isEqualTo(before.get("current_stage_number"));
    assertThat(row(id).get("tree_status")).isEqualTo(to);
    assertThat(Instant.parse(body.path("updatedAt").asText()))
        .isBetween(requestedAt, Instant.now());
    assertThat(Instant.parse(body.path("updatedAt").asText())).isEqualTo(updatedAt(id));
    assertThat(updatedAt(id)).isAfter(((java.sql.Timestamp) before.get("updated_at")).toInstant());
    assertThat(trees.findById(id).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt(id));
    if (to.equals("ACTIVE")) {
      assertThat(body.path("pausedAt").isNull()).isTrue();
      assertThat(body.path("endedAt").isNull()).isTrue();
      assertThat(row(id).get("paused_at")).isNull();
      assertThat(row(id).get("ended_at")).isNull();
    } else {
      String field = to.equals("PAUSED") ? "pausedAt" : "endedAt";
      Instant event = Instant.parse(body.path(field).asText());
      assertThat(event).isBetween(requestedAt, Instant.now());
      String column = to.equals("PAUSED") ? "paused_at" : "ended_at";
      var stored =
          jdbc.queryForObject(
              "SELECT " + column + " FROM user_hobbies WHERE user_hobby_id=?",
              (rs, n) -> rs.getTimestamp(1).toInstant(),
              id);
      assertThat(stored).isEqualTo(event.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
      if (to.equals("PAUSED")) assertThat(body.path("endedAt").isNull()).isTrue();
      if (to.equals("ENDED")) {
        assertThat(body.path("pausedAt").isNull()).isTrue();
        assertThat(row(id).get("paused_at")).isNull();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "PAUSED", "ENDED"})
  void sameStatusIsANoOpIncludingStoredTimestampsAndPostgresRowVersion(String status)
      throws Exception {
    var owner = signIn();
    long id = tree(owner.id(), status);
    var before = row(id);
    var first = success(patch(owner.token(), id, status));
    var second = success(patch(owner.token(), id, status));
    assertThat(row(id)).isEqualTo(before); // xmin detects even a same-value SQL UPDATE.
    for (String field :
        List.of(
            "userHobbyId", "treeStatus", "currentStageNumber", "pausedAt", "endedAt", "updatedAt"))
      assertThat(second.path(field)).as(field).isEqualTo(first.path(field));
    assertThat(Instant.parse(second.path("updatedAt").asText())).isEqualTo(updatedAt(id));
  }

  @Test
  void rejectsEndedToPausedWithoutChangingAnyStoredValue() throws Exception {
    var owner = signIn();
    long id = tree(owner.id(), "ENDED");
    var before = row(id);
    assertError(patch(owner.token(), id, "PAUSED"), 409, "TREE409_3");
    assertThat(row(id)).isEqualTo(before);
  }

  @Test
  void reactivationAtFourSlotsFillsTheFifthAndAtFiveSlotsIsRejected() throws Exception {
    var owner = signIn();
    occupied(owner.id(), 4);
    long first = tree(owner.id(), "ENDED");
    long second = tree(owner.id(), "ENDED");
    success(patch(owner.token(), first, "ACTIVE"));
    var before = row(second);
    assertError(patch(owner.token(), second, "ACTIVE"), 409, "TREE409_2");
    assertThat(row(second)).isEqualTo(before);
    assertThat(occupiedCount(owner.id())).isEqualTo(5);
    success(patch(owner.token(), second, "ENDED")); // No-op requires no additional slot.
  }

  @Test
  void pausedResumeAtFiveSlotsNeedsNoNewSlotAndEndingReleasesOneForPlanting() throws Exception {
    var owner = signIn();
    occupied(owner.id(), 4);
    long id = tree(owner.id(), "PAUSED");
    success(patch(owner.token(), id, "ACTIVE"));
    assertThat(occupiedCount(owner.id())).isEqualTo(5);
    success(patch(owner.token(), id, "PAUSED"));
    assertThat(occupiedCount(owner.id())).isEqualTo(5);
    long candidate = hobby(owner.id());
    assertError(plant(owner.token(), candidate), 409, "TREE409_2");
    success(patch(owner.token(), id, "ENDED"));
    assertThat(occupiedCount(owner.id())).isEqualTo(4);
    assertThat(plant(owner.token(), candidate).statusCode()).isEqualTo(201);
    long endedHobby = ((Number) row(id).get("hobby_id")).longValue();
    assertError(plant(owner.token(), endedHobby), 409, "TREE409_1");
  }

  @Test
  void preservesEveryActivityColumnAndApi18And20GrowthWhileChangingStatuses() throws Exception {
    var owner = signIn();
    long id = tree(owner.id(), "ACTIVE");
    long hobbyId = ((Number) row(id).get("hobby_id")).longValue();
    long stage =
        jdbc.queryForObject(
            "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,3,'성장') RETURNING hobby_stage_id",
            Long.class,
            hobbyId);
    for (boolean counted : List.of(false, true)) {
      long activity =
          jdbc.queryForObject(
              "INSERT INTO activities(hobby_stage_id,activity_code,activity_name) VALUES (?,?,'기록 보존') RETURNING activity_id",
              Long.class,
              stage,
              UUID.randomUUID().toString());
      jdbc.update(
          """
          INSERT INTO activity_records(user_hobby_id,activity_id,activity_status,step_progress,
            growth_counted,growth_stage_number,started_at,finished_at,updated_at)
          VALUES (?, ?, ?, '{"steps":[1]}'::jsonb, ?, ?, '2026-11-01T00:00:00Z',
            CASE WHEN ? THEN '2026-11-01T01:00:00Z'::timestamptz END,'2026-11-01T02:00:00Z')
          """,
          id,
          activity,
          counted ? "COMPLETED" : "IN_PROGRESS",
          counted,
          counted ? 3 : null,
          counted);
    }
    var records = activityRows(id);
    var originalDetail = result(get(owner.token(), PATH + "/" + id));
    for (String status : List.of("PAUSED", "ENDED", "ACTIVE")) {
      success(patch(owner.token(), id, status));
      assertThat(activityRows(id)).isEqualTo(records);
      var detailResponse = get(owner.token(), PATH + "/" + id);
      assertThat(detailResponse.statusCode()).isEqualTo(200);
      var detail = result(detailResponse);
      assertThat(detail.path("treeStatus").asText()).isEqualTo(status);
      for (String field :
          List.of(
              "currentStageNumber",
              "currentStageName",
              "distinctCompletedActivityCount",
              "currentStageCompletedCount",
              "requiredCountForNextStage",
              "remainingCountForNextStage",
              "currentActivityRecord",
              "lastActivityAt",
              "plantedAt"))
        assertThat(detail.path(field)).as(field).isEqualTo(originalDetail.path(field));
      var listing = result(get(owner.token(), PATH));
      assertThat(listing.path("items").get(0).path("treeStatus").asText()).isEqualTo(status);
      assertThat(listing.path("occupiedTreeCount").asLong())
          .isEqualTo(status.equals("ENDED") ? 0 : 1);
      assertThat(listing.path("items").get(0).path("distinctCompletedActivityCount").asLong())
          .isEqualTo(1);
      var filtered = result(get(owner.token(), PATH + "?status=" + status));
      assertThat(filtered.path("items").size()).isEqualTo(1);
    }
  }

  @Test
  void plantingListingDetailAndStatusAgreeOnExternalOneWhileKeepingStoredZero() throws Exception {
    var owner = signIn();
    var planted = plant(owner.token(), hobby(owner.id()));
    assertThat(planted.statusCode()).isEqualTo(201);
    long id = result(planted).path("userHobbyId").asLong();
    assertThat(result(planted).path("currentStageNumber").asInt()).isEqualTo(1);
    assertThat(updatedAt(id)).isNotNull();
    assertThat(trees.findById(id).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt(id));
    var initial = success(patch(owner.token(), id, "ACTIVE"));
    assertThat(Instant.parse(initial.path("updatedAt").asText())).isEqualTo(updatedAt(id));
    for (String status : List.of("PAUSED", "ENDED", "ACTIVE")) {
      assertThat(success(patch(owner.token(), id, status)).path("currentStageNumber").asInt())
          .isEqualTo(1);
      assertThat(result(get(owner.token(), PATH + "/" + id)).path("currentStageNumber").asInt())
          .isEqualTo(1);
      var listed = result(get(owner.token(), PATH)).path("items").get(0);
      assertThat(listed.path("currentStageNumber").asInt()).isEqualTo(1);
      assertThat(((Number) row(id).get("current_stage_number")).intValue()).isZero();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void allStoredStagesKeepTheirNamesIdsAndGrowthAcrossReadsAndStatusChanges(int storedStage)
      throws Exception {
    var owner = signIn();
    long id = tree(owner.id(), "ACTIVE");
    long hobbyId = ((Number) row(id).get("hobby_id")).longValue();
    jdbc.update(
        "UPDATE user_hobbies SET current_stage_number=? WHERE user_hobby_id=?", storedStage, id);
    long stageId =
        jdbc.queryForObject(
            "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,?,'실제 저장 단계') RETURNING hobby_stage_id",
            Long.class,
            hobbyId,
            storedStage);
    jdbc.update(
        "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,?,'다른 단계')",
        hobbyId,
        (storedStage + 1) % 6);
    long activityId =
        jdbc.queryForObject(
            "INSERT INTO activities(hobby_stage_id,activity_code,activity_name) VALUES (?,?,'성장 검증') RETURNING activity_id",
            Long.class,
            stageId,
            UUID.randomUUID().toString());
    jdbc.update(
        "INSERT INTO activity_records(user_hobby_id,activity_id,activity_status,growth_counted,growth_stage_number) VALUES (?,?,'COMPLETED',true,?)",
        id,
        activityId,
        storedStage);
    var recordsBefore = activityRows(id);
    var stagesBefore =
        jdbc.queryForList(
            "SELECT * FROM hobby_stages WHERE hobby_id=? ORDER BY stage_number", hobbyId);

    for (String status : List.of("ACTIVE", "PAUSED", "ENDED", "ACTIVE")) {
      var changed = success(patch(owner.token(), id, status));
      assertThat(changed.path("currentStageNumber").asInt()).isEqualTo(storedStage + 1);
      var detailResponse = get(owner.token(), PATH + "/" + id);
      assertThat(detailResponse.statusCode()).isEqualTo(200);
      var detail = result(detailResponse);
      var listResponse = get(owner.token(), PATH);
      assertThat(listResponse.statusCode()).isEqualTo(200);
      var listed = result(listResponse).path("items").get(0);
      for (var body : List.of(listed, detail)) {
        assertThat(body.path("userHobbyId").asLong()).isEqualTo(id);
        assertThat(body.path("hobbyId").asLong()).isEqualTo(hobbyId);
        assertThat(body.path("currentStageNumber").asInt()).isEqualTo(storedStage + 1);
        assertThat(body.path("currentStageName").asText()).isEqualTo("실제 저장 단계");
        assertThat(body.path("distinctCompletedActivityCount").asLong()).isEqualTo(1);
        assertThat(body.path("remainingCountForNextStage").asLong())
            .isEqualTo(storedStage == 5 ? 0 : 2);
        if (storedStage == 5) {
          assertThat(body.path("requiredCountForNextStage").isNull()).isTrue();
        } else {
          assertThat(body.path("requiredCountForNextStage").asInt()).isEqualTo(3);
        }
      }
      assertThat(detail.path("totalStages").asInt()).isEqualTo(6);
      assertThat(detail.path("isMaxStage").asBoolean()).isEqualTo(storedStage == 5);
      assertThat(detail.path("currentStageCompletedCount").asLong()).isEqualTo(1);
      assertThat(detail.path("currentStageRequiredCount"))
          .isEqualTo(detail.path("requiredCountForNextStage"));
      assertThat(((Number) row(id).get("current_stage_number")).intValue()).isEqualTo(storedStage);
      assertThat(activityRows(id)).isEqualTo(recordsBefore);
      assertThat(
              jdbc.queryForList(
                  "SELECT * FROM hobby_stages WHERE hobby_id=? ORDER BY stage_number", hobbyId))
          .isEqualTo(stagesBefore);
    }
  }

  @Test
  void serializesTwoRestartsAtFourSlotsOnSeparatePostgresConnections() throws Exception {
    var owner = signIn();
    occupied(owner.id(), 4);
    long first = tree(owner.id(), "ENDED");
    long second = tree(owner.id(), "ENDED");
    var responses =
        compete(
            owner.id(),
            () -> patch(owner.token(), first, "ACTIVE"),
            () -> patch(owner.token(), second, "ACTIVE"));
    assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
    assertThat(responses.stream().filter(r -> r.statusCode() == 409).map(this::code))
        .containsExactly("TREE409_2");
    assertThat(occupiedCount(owner.id())).isEqualTo(5);
  }

  @Test
  void serializesPlantAndRestartUsingTheSameUserLock() throws Exception {
    var owner = signIn();
    occupied(owner.id(), 4);
    long ended = tree(owner.id(), "ENDED");
    long candidate = hobby(owner.id());
    var responses =
        compete(
            owner.id(),
            () -> plant(owner.token(), candidate),
            () -> patch(owner.token(), ended, "ACTIVE"));
    assertThat(responses.stream().filter(r -> r.statusCode() < 300).count()).isEqualTo(1);
    assertThat(responses.stream().filter(r -> r.statusCode() == 409).map(this::code))
        .containsExactly("TREE409_2");
    assertThat(occupiedCount(owner.id())).isEqualTo(5);
  }

  @Test
  void conflictingPauseAndEndRequestsFollowASerialOrderWithoutLostUpdates() throws Exception {
    var owner = signIn();
    long id = tree(owner.id(), "ACTIVE");
    var responses =
        compete(
            owner.id(),
            () -> patch(owner.token(), id, "PAUSED"),
            () -> patch(owner.token(), id, "ENDED"));
    assertThat(responses.get(1).statusCode()).isEqualTo(200);
    assertThat(responses.get(0).statusCode()).isIn(200, 409);
    if (responses.get(0).statusCode() == 409)
      assertThat(code(responses.get(0))).isEqualTo("TREE409_3");
    var stored = row(id);
    assertThat(stored.get("tree_status")).isEqualTo("ENDED");
    assertThat(stored.get("ended_at")).isNotNull();
    assertThat(((Number) stored.get("current_stage_number")).intValue()).isEqualTo(3);
  }

  @ParameterizedTest
  @CsvSource({"SUSPENDED,AUTH403_1", "WITHDRAWN,AUTH403_3", "PENDING_ONBOARDING,AUTH403_2"})
  void rechecksAccountAfterLockWaitUsingLatestCommittedState(String status, String expected)
      throws Exception {
    var owner = signIn();
    long id = tree(owner.id(), "ACTIVE");
    var before = row(id);
    try (var executor = Executors.newSingleThreadExecutor();
        var lock = lock("users", "user_id", owner.id())) {
      var pending = executor.submit(() -> patch(owner.token(), id, "ENDED"));
      try {
        awaitBlockedRequests(1, "users");
        changeAccount(lock, owner.id(), status);
        lock.commit();
        assertError(pending.get(15, TimeUnit.SECONDS), 403, expected);
        assertThat(row(id)).isEqualTo(before);
      } finally {
        lock.rollback();
      }
    }
  }

  @Test
  void rechecksOwnershipAfterTheTreeLockWait() throws Exception {
    var owner = signIn();
    long other = otherUser();
    long id = tree(owner.id(), "ACTIVE");
    try (var executor = Executors.newSingleThreadExecutor();
        var lock = lock("user_hobbies", "user_hobby_id", id)) {
      var pending = executor.submit(() -> patch(owner.token(), id, "PAUSED"));
      try {
        awaitBlockedRequests(1, "user_hobbies");
        try (var statement =
            lock.prepareStatement("UPDATE user_hobbies SET user_id=? WHERE user_hobby_id=?")) {
          statement.setLong(1, other);
          statement.setLong(2, id);
          statement.executeUpdate();
        }
        lock.commit();
        assertError(pending.get(15, TimeUnit.SECONDS), 404, "TREE404_1");
        assertThat(row(id).get("tree_status")).isEqualTo("ACTIVE");
      } finally {
        lock.rollback();
      }
    }
  }

  @Test
  void readsCommittedTreeStateAfterWaitingInsteadOfOverwritingIt() throws Exception {
    var owner = signIn();
    long id = tree(owner.id(), "ACTIVE");
    try (var executor = Executors.newSingleThreadExecutor();
        var lock = lock("user_hobbies", "user_hobby_id", id)) {
      var pending = executor.submit(() -> patch(owner.token(), id, "PAUSED"));
      try {
        awaitBlockedRequests(1, "user_hobbies");
        try (var statement =
            lock.prepareStatement(
                "UPDATE user_hobbies SET tree_status='ENDED',ended_at=?::timestamptz WHERE user_hobby_id=?")) {
          statement.setString(1, ENDED_AT);
          statement.setLong(2, id);
          statement.executeUpdate();
        }
        lock.commit();
        assertError(pending.get(15, TimeUnit.SECONDS), 409, "TREE409_3");
        assertThat(row(id).get("tree_status")).isEqualTo("ENDED");
      } finally {
        lock.rollback();
      }
    }
  }

  @Test
  void simultaneousSameStatusRequestsShareOnePersistedEventTime() throws Exception {
    var owner = signIn();
    long id = tree(owner.id(), "ACTIVE");
    var responses =
        compete(
            owner.id(),
            () -> patch(owner.token(), id, "PAUSED"),
            () -> patch(owner.token(), id, "PAUSED"));
    var first = success(responses.get(0));
    var second = success(responses.get(1));
    assertThat(first.path("pausedAt")).isEqualTo(second.path("pausedAt"));
    var before = row(id);
    success(patch(owner.token(), id, "PAUSED"));
    assertThat(row(id)).isEqualTo(before);
  }

  @Test
  void statusChangesDoNotRequireAHobbyRowLock() throws Exception {
    var owner = signIn();
    long id = tree(owner.id(), "ACTIVE");
    long hobbyId = ((Number) row(id).get("hobby_id")).longValue();
    try (var executor = Executors.newSingleThreadExecutor();
        var lock = lock("hobbies", "hobby_id", hobbyId)) {
      try {
        success(
            executor.submit(() -> patch(owner.token(), id, "PAUSED")).get(10, TimeUnit.SECONDS));
      } finally {
        lock.rollback();
      }
    }
  }

  @Test
  void anotherOwnersTreeAndMissingTreeUseTheSame404WithoutChanges() throws Exception {
    var owner = signIn();
    long foreign = tree(otherUser(), "ACTIVE");
    var before = row(foreign);
    assertError(patch(owner.token(), foreign, "ENDED"), 404, "TREE404_1");
    assertError(patch(owner.token(), Long.MAX_VALUE, "ENDED"), 404, "TREE404_1");
    assertThat(row(foreign)).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
  void rejectsInvalidIds(String id) throws Exception {
    assertError(rawPatch(signIn().token(), id, "{\"status\":\"PAUSED\"}"), 400, "COMMON400_1");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"status\":null}",
        "{\"status\":\"\"}",
        "{\"status\":\"paused\"}",
        "{\"status\":\"UNKNOWN\"}",
        "{\"status\":0}",
        "{\"status\":true}",
        "{\"status\":[]}",
        "{\"status\":{}}",
        "[]",
        "null",
        "{broken",
        ""
      })
  void rejectsInvalidBodiesAndDoesNotWrite(String body) throws Exception {
    var owner = signIn();
    long id = tree(owner.id(), "ACTIVE");
    var before = row(id);
    var response = rawPatch(owner.token(), Long.toString(id), body);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    assertThat(code(response)).isEqualTo("COMMON400_1");
    assertThat(row(id)).isEqualTo(before);
  }

  @Test
  void requiresValidUnexpiredUnrevokedJwt() throws Exception {
    var owner = signIn();
    long id = tree(owner.id(), "ACTIVE");
    var before = row(id);
    assertError(patch(null, id, "ENDED"), 401, "AUTH401_2");
    assertError(patch("invalid", id, "ENDED"), 401, "AUTH401_2");
    var issuer =
        new JwtTokenService(
            encoder, authProperties, Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));
    String sid = decoder.decode(owner.token()).getClaimAsString("sid");
    assertError(
        patch(issuer.issue(users.findById(owner.id()).orElseThrow(), sid, "access"), id, "ENDED"),
        401,
        "AUTH401_2");
    redis.delete("auth:session:" + owner.id());
    assertError(patch(owner.token(), id, "ENDED"), 401, "AUTH401_2");
    assertThat(row(id)).isEqualTo(before);
  }

  @ParameterizedTest
  @CsvSource({"SUSPENDED,AUTH403_1", "WITHDRAWN,AUTH403_3", "PENDING_ONBOARDING,AUTH403_2"})
  void rejectsIneligibleAccounts(String status, String expected) throws Exception {
    var owner = signIn();
    long id = tree(owner.id(), "ACTIVE");
    try (var connection = dataSource.getConnection()) {
      changeAccount(connection, owner.id(), status);
    }
    var before = row(id);
    assertError(patch(owner.token(), id, "ENDED"), 403, expected);
    assertThat(row(id)).isEqualTo(before);
  }

  private SignedInUser signIn() throws Exception {
    var response = login();
    assertThat(response.statusCode()).isEqualTo(200);
    long id = result(response).path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return new SignedInUser(id, result(response).path("accessToken").asText());
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
    return jdbc.queryForObject(
        "INSERT INTO hobbies(hobby_code,hobby_name) VALUES (?,'상태 테스트') RETURNING hobby_id",
        Long.class,
        "profile-test-" + owner + "-" + UUID.randomUUID().toString().substring(0, 12));
  }

  private long tree(long owner, String status) {
    return jdbc.queryForObject(
        """
        INSERT INTO user_hobbies(user_id,hobby_id,tree_status,current_stage_number,paused_at,ended_at)
        VALUES (?,?,?,3,CASE WHEN ? IN ('PAUSED','ENDED') THEN ?::timestamptz END,
          CASE WHEN ?='ENDED' THEN ?::timestamptz END) RETURNING user_hobby_id
        """,
        Long.class,
        owner,
        hobby(owner),
        status,
        status,
        PAUSED_AT,
        status,
        ENDED_AT);
  }

  private void occupied(long userId, int count) {
    for (int i = 0; i < count; i++) tree(userId, i % 2 == 0 ? "ACTIVE" : "PAUSED");
  }

  private long occupiedCount(long userId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM user_hobbies WHERE user_id=? AND tree_status IN ('ACTIVE','PAUSED')",
        Long.class,
        userId);
  }

  private Map<String, Object> row(long id) {
    return jdbc.queryForMap(
        "SELECT *,xmin::text AS row_version FROM user_hobbies WHERE user_hobby_id=?", id);
  }

  private Instant updatedAt(long id) {
    return jdbc.queryForObject(
        "SELECT updated_at FROM user_hobbies WHERE user_hobby_id=?",
        (rs, n) -> rs.getTimestamp(1).toInstant(),
        id);
  }

  @Test
  void waitingWriterUsesActualWriteTimeAfterThePreviousWriterInsteadOfTransactionStart()
      throws Exception {
    var owner = signIn();
    long id = tree(owner.id(), "ACTIVE");
    try (var executor = Executors.newSingleThreadExecutor();
        var lock = lock("users", "user_id", owner.id())) {
      var pending = executor.submit(() -> patch(owner.token(), id, "ENDED"));
      try {
        awaitBlockedRequests(1, "users");
        Instant previous;
        try (var statement =
            lock.prepareStatement(
                """
            UPDATE user_hobbies SET tree_status='PAUSED',paused_at=clock_timestamp()
            WHERE user_hobby_id=? RETURNING updated_at
            """)) {
          statement.setLong(1, id);
          try (var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            previous = rows.getTimestamp(1).toInstant();
          }
        }
        lock.commit();
        var response = success(pending.get(15, TimeUnit.SECONDS));
        assertThat(Instant.parse(response.path("updatedAt").asText()))
            .isEqualTo(updatedAt(id))
            .isAfter(previous);
        assertThat(response.path("pausedAt").isNull()).isTrue();
      } finally {
        lock.rollback();
      }
    }
  }

  private List<Map<String, Object>> activityRows(long id) {
    return jdbc.queryForList(
        "SELECT *,xmin::text AS row_version FROM activity_records WHERE user_hobby_id=? ORDER BY activity_record_id",
        id);
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update(
        "DELETE FROM user_hobbies WHERE hobby_id IN (SELECT hobby_id FROM hobbies WHERE hobby_code LIKE ?)",
        "profile-test-" + userId + "-%");
    jdbc.update(
        """
        DELETE FROM activities WHERE hobby_stage_id IN
          (SELECT hs.hobby_stage_id FROM hobby_stages hs JOIN hobbies h USING(hobby_id) WHERE h.hobby_code LIKE ?)
        """,
        "profile-test-" + userId + "-%");
    super.cleanupSeededHobbies(userId);
  }

  private HttpResponse<String> patch(String token, long id, String status) throws Exception {
    return rawPatch(token, Long.toString(id), "{\"status\":\"" + status + "\"}");
  }

  private HttpResponse<String> rawPatch(String token, String id, String body) throws Exception {
    return send(token, PATH + "/" + id + "/status", "PATCH", body);
  }

  private HttpResponse<String> plant(String token, long hobbyId) throws Exception {
    return send(token, PATH, "POST", "{\"hobbyId\":" + hobbyId + "}");
  }

  private HttpResponse<String> get(String token, String path) throws Exception {
    return send(token, path, "GET", null);
  }

  private HttpResponse<String> send(String token, String path, String method, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(25))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) request.header("Content-Type", "application/json");
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode success(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var root = mapper.readTree(response.body());
    assertThat(root.propertyNames())
        .containsExactlyInAnyOrder("isSuccess", "code", "message", "result");
    assertThat(root.path("isSuccess").asBoolean()).isTrue();
    assertThat(root.path("code").asText()).isEqualTo("TREE200_3");
    assertThat(root.path("message").asText()).isEqualTo("취미 나무 상태가 변경되었습니다.");
    return result(response);
  }

  private String code(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("code").asText();
  }

  private void assertError(HttpResponse<String> response, int status, String expected) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    assertThat(code(response)).isEqualTo(expected);
    assertThat(result(response).isNull()).isTrue();
  }

  private Connection lock(String table, String key, long id) throws Exception {
    var connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (var statement =
        connection.prepareStatement(
            "SELECT " + key + " FROM " + table + " WHERE " + key + "=? FOR UPDATE")) {
      statement.setLong(1, id);
      statement.executeQuery().close();
    }
    return connection;
  }

  private void changeAccount(Connection connection, long id, String status) throws Exception {
    try (var statement =
        connection.prepareStatement(
            """
        UPDATE users SET account_status=?,
          withdrawn_at=CASE WHEN ?='WITHDRAWN' THEN now() END,
          restorable_until=CASE WHEN ?='WITHDRAWN' THEN now()+INTERVAL '720 hours' END,
          status_before_withdrawal=CASE WHEN ?='WITHDRAWN' THEN 'ACTIVE' END WHERE user_id=?
        """)) {
      for (int i = 1; i <= 4; i++) statement.setString(i, status);
      statement.setLong(5, id);
      statement.executeUpdate();
    }
  }

  private List<HttpResponse<String>> compete(
      long userId, Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second)
      throws Exception {
    try (var executor = Executors.newFixedThreadPool(2);
        var lock = lock("users", "user_id", userId)) {
      var left = executor.submit(first);
      var right = executor.submit(second);
      try {
        awaitBlockedRequests(2, "users");
        assertThat(left.isDone()).isFalse();
        assertThat(right.isDone()).isFalse();
      } finally {
        lock.rollback();
      }
      return List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
    }
  }

  private void awaitBlockedRequests(int expected, String table) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    do {
      var pids =
          jdbc.queryForList(
              """
          SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()
            AND wait_event_type='Lock' AND query ILIKE ? AND query ILIKE '%for%update%'
          """,
              Long.class, "%from " + table + "%");
      if (pids.size() >= expected) {
        assertThat(pids).doesNotHaveDuplicates();
        return;
      }
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);
    fail(
        "Expected "
            + expected
            + " transactions waiting on separate PostgreSQL connections for "
            + table);
  }

  private record SignedInUser(long id, String token) {}
}
