package com.hobbytree.hobbytree.domain.user;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.user.entity.*;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

// JUnit5 does not reliably inherit @EnabledIfEnvironmentVariable from IntegrationTestSupport in
// a plain build, so it must be repeated here or this class's tests try (and fail) to run.
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class UserIntegrationTests extends IntegrationTestSupport {

  HttpResponse<String> restore(String token) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/restore"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(
                        Map.of("restoreToken", token, "agreeToRestore", true))))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  void withdraw(long id, String statusBeforeWithdrawal) {
    jdbc.update(
        "UPDATE users SET account_status='WITHDRAWN',withdrawn_at=now(),"
            + "restorable_until=now()+interval '720 hours',status_before_withdrawal=? WHERE user_id=?",
        statusBeforeWithdrawal,
        id);
  }

  HttpResponse<String> withdrawMe(String accessToken) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me"))
            .header("Authorization", "Bearer " + accessToken)
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void withdrawRevokesSessionAndRecordsRestorableState() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String access = signedIn.path("accessToken").asText();

    var response = withdrawMe(access);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("USER200_3");
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var body = result(response);
    assertThat(body.path("userId").asLong()).isEqualTo(id);
    assertThat(body.path("memberStatus").asText()).isEqualTo("WITHDRAWN");
    assertThat(body.path("withdrawnAt").asText()).isNotBlank();
    assertThat(body.path("restorableUntil").asText()).isNotBlank();

    var user = users.findById(id).orElseThrow();
    assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.WITHDRAWN);
    assertThat(user.getStatusBeforeWithdrawal()).isEqualTo(AccountStatus.PENDING_ONBOARDING);
    assertThat(user.getRestorableUntil()).isEqualTo(user.getWithdrawnAt().plusSeconds(720L * 3600));
    assertThat(redis.hasKey("auth:session:" + id)).isFalse();
    assertThat(protectedStatus(access)).isEqualTo(401);

    // Full journey: a withdrawn member logging in again gets the restore guide, and the
    // restore endpoint actually works against the row this test just transitioned.
    var recovery = login();
    assertThat(recovery.statusCode()).isEqualTo(409);
    assertThat(result(recovery).path("restorable").asBoolean()).isTrue();
    assertThat(restore(result(recovery).path("restoreToken").asText()).statusCode()).isEqualTo(200);
  }

  @Test
  void withdrawnAccountWithLingeringSessionIsRejectedWithForbidden() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String access = signedIn.path("accessToken").asText();
    // Simulate account state changing to WITHDRAWN without going through withdraw(), so the
    // Redis session (and thus this access token) is still current when AuthFilter checks it.
    withdraw(id, "ACTIVE");

    var response = withdrawMe(access);
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_3");
  }

  @Test
  void concurrentWithdrawOnlySucceedsOnce() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String access = signedIn.path("accessToken").asText();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<HttpResponse<String>> call =
          () -> {
            start.await();
            return withdrawMe(access);
          };
      var a = executor.submit(call);
      var b = executor.submit(call);
      start.countDown();
      var responses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
      assertThat(responses.stream().filter(r -> r.statusCode() == 200).count()).isEqualTo(1);
      // The loser can lose at three different points: AuthFilter's isCurrentSession check
      // (401, if the winner already revoked the session), AuthFilter's own status read seeing
      // WITHDRAWN after passing isCurrentSession but before reaching the controller (403), or
      // UserService's locked re-check after passing AuthFilter entirely (409).
      assertThat(responses.stream().map(HttpResponse::statusCode).toList())
          .allSatisfy(code -> assertThat(code).isIn(200, 401, 403, 409));

      assertThat(users.findById(id).orElseThrow().getAccountStatus())
          .isEqualTo(AccountStatus.WITHDRAWN);
      assertThat(redis.hasKey("auth:session:" + id)).isFalse();
    }
  }

  HttpResponse<String> getMyProfile(String accessToken) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me"))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void profileReturnsDefaultsForNewPendingOnboardingUser() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);

    var response = getMyProfile(signedIn.path("accessToken").asText());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("USER200_1");
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var body = result(response);
    assertThat(body.path("userId").asLong()).isEqualTo(id);
    assertThat(body.path("nickname").isNull()).isTrue();
    assertThat(body.path("profileImageUrl").isNull()).isTrue();
    assertThat(body.path("provider").asText()).isEqualTo("KAKAO");
    assertThat(body.path("ageRange").isNull()).isTrue();
    assertThat(body.path("memberStatus").asText()).isEqualTo("PENDING_ONBOARDING");
    assertThat(body.path("onboardingCompleted").asBoolean()).isFalse();
    assertThat(body.path("treeCount").asLong()).isEqualTo(0);
    assertThat(body.path("maxTreeCount").asInt()).isEqualTo(5);
    var settings = body.path("notificationSettings");
    assertThat(settings.path("activityReminderEnabled").asBoolean()).isTrue();
    assertThat(settings.path("treeGrowthEnabled").asBoolean()).isTrue();
    assertThat(settings.path("experienceOpenEnabled").asBoolean()).isTrue();
    assertThat(settings.path("announcementEnabled").asBoolean()).isTrue();
    var quietHours = settings.path("quietHours");
    assertThat(quietHours.path("enabled").asBoolean()).isFalse();
    assertThat(quietHours.path("start").asText()).isEqualTo("22:00");
    assertThat(quietHours.path("end").asText()).isEqualTo("08:00");
    assertThat(quietHours.path("timeZone").asText()).isEqualTo("Asia/Seoul");
    assertThat(body.path("createdAt").asText()).isNotBlank();
  }

  @Test
  void profileMarksOnboardingCompletedWhenActive() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);

    var body = result(getMyProfile(signedIn.path("accessToken").asText()));
    assertThat(body.path("memberStatus").asText()).isEqualTo("ACTIVE");
    assertThat(body.path("onboardingCompleted").asBoolean()).isTrue();
  }

  @Test
  void profileTreeCountIncludesOnlyActiveAndPausedTreesForTheAuthenticatedUser() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    seedUserHobbies(id, List.of("ACTIVE", "PAUSED", "ENDED"));

    // A different member's ACTIVE/PAUSED trees must not leak into this user's count.
    var otherSignedIn =
        result(login("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}"));
    long otherId = otherSignedIn.path("userId").asLong();
    createdUsers.add(otherId);
    seedUserHobbies(otherId, List.of("ACTIVE", "PAUSED"));

    var body = result(getMyProfile(signedIn.path("accessToken").asText()));
    assertThat(body.path("treeCount").asLong()).isEqualTo(2);
    // Seeded hobbies/user_hobbies rows are cleaned up by @AfterEach (for both users, via
    // createdUsers), so this still happens if the assertion above fails.
  }

  // UNIQUE(user_id, hobby_id) means each status needs its own hobby row.
  void seedUserHobbies(long userId, List<String> statuses) {
    for (String status : statuses) {
      long hobbyId =
          jdbc.queryForObject(
              "INSERT INTO hobbies (hobby_code, hobby_name) VALUES (?, ?) RETURNING hobby_id",
              Long.class,
              "profile-test-" + userId + "-" + status,
              "프로필 테스트 취미 " + status);
      jdbc.update(
          "INSERT INTO user_hobbies (user_id, hobby_id, tree_status) VALUES (?, ?, ?)",
          userId,
          hobbyId,
          status);
    }
  }

  HttpResponse<String> updateProfile(String accessToken, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me"))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void updateProfileAppliesOnlyProvidedFieldsAndTrimsNickname() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String access = signedIn.path("accessToken").asText();

    var first = updateProfile(access, "{\"nickname\":\"  즐거운수달  \"}");
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(first.body()).path("code").asText()).isEqualTo("USER200_2");
    assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
    var firstBody = result(first);
    assertThat(firstBody.path("userId").asLong()).isEqualTo(id);
    assertThat(firstBody.path("nickname").asText()).isEqualTo("즐거운수달");
    assertThat(firstBody.path("profileImageUrl").isNull()).isTrue();
    assertThat(firstBody.path("ageRange").isNull()).isTrue();
    String firstUpdatedAt = firstBody.path("updatedAt").asText();
    assertThat(firstUpdatedAt).isNotBlank();

    // Only ageRange is sent this time; the nickname set above must survive untouched.
    var second = updateProfile(access, "{\"ageRange\":\"TWENTIES\"}");
    assertThat(second.statusCode()).isEqualTo(200);
    var secondBody = result(second);
    assertThat(secondBody.path("nickname").asText()).isEqualTo("즐거운수달");
    assertThat(secondBody.path("ageRange").asText()).isEqualTo("TWENTIES");
    assertThat(secondBody.path("updatedAt").asText()).isNotEqualTo(firstUpdatedAt);

    var user = users.findById(id).orElseThrow();
    assertThat(user.getNickname()).isEqualTo("즐거운수달");
    assertThat(user.getAgeRange()).isEqualTo("TWENTIES");

    assertConcurrentProfileChangesArePreserved(id, access);
  }

  void assertConcurrentProfileChangesArePreserved(long id, String access) throws Exception {
    try (var executor = Executors.newSingleThreadExecutor();
        var connection = jdbc.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      try {
        int blockingPid;
        try (var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
          rows.next();
          blockingPid = rows.getInt(1);
        }
        // Keep a nickname change uncommitted while a separate HTTP request changes ageRange.
        try (var statement =
            connection.prepareStatement("UPDATE users SET nickname = ? WHERE user_id = ?")) {
          statement.setString(1, "ConcurrentNick");
          statement.setLong(2, id);
          statement.executeUpdate();
        }
        var pending = executor.submit(() -> updateProfile(access, "{\"ageRange\":\"THIRTIES\"}"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean blocked = false;
        while (System.nanoTime() < deadline && !pending.isDone()) {
          blocked =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity"
                          + " WHERE ? = ANY(pg_blocking_pids(pid)))",
                      Boolean.class,
                      blockingPid));
          if (blocked) break;
          Thread.sleep(25);
        }
        assertThat(blocked).as("profile request waits for the concurrent transaction").isTrue();
        connection.commit();
        var response = pending.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(result(response).path("nickname").asText()).isEqualTo("ConcurrentNick");
        var updated = users.findById(id).orElseThrow();
        assertThat(updated.getNickname()).isEqualTo("ConcurrentNick");
        assertThat(updated.getAgeRange()).isEqualTo("THIRTIES");
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  void updateProfileRejectsEmptyBody() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String access = signedIn.path("accessToken").asText();
    updateProfile(access, "{\"nickname\":\"즐거운수달\"}");

    var response = updateProfile(access, "{}");
    assertThat(response.statusCode()).isEqualTo(400);
    // The rejected request must not have cleared the nickname set above.
    assertThat(users.findById(id).orElseThrow().getNickname()).isEqualTo("즐거운수달");
  }

  @Test
  void updateProfileRejectsInvalidNicknameAndAgeRange() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String access = signedIn.path("accessToken").asText();

    for (String body :
        List.of(
            "{\"nickname\":\"a\"}", // too short
            "{\"nickname\":\"가나다라마바사아자차카타파\"}", // 13자, too long
            "{\"nickname\":\"nick name\"}", // space not allowed
            "{\"nickname\":\"nick!\"}", // punctuation not allowed
            "{\"nickname\":123}",
            "{\"nickname\":true}",
            "{\"ageRange\":\"TEENAGER\"}", // not a valid enum value
            "{\"ageRange\":123}",
            "{broken")) {
      assertThat(updateProfile(access, body).statusCode()).isEqualTo(400);
    }

    // None of the rejected requests above actually changed anything.
    assertThat(users.findById(id).orElseThrow().getNickname()).isNull();
  }
}
