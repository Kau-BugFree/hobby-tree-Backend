package com.hobbytree.hobbytree.domain.experience;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.experience.dto.ConfirmAttendanceResponse;
import com.hobbytree.hobbytree.domain.experience.service.ExperienceAttendanceService;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class ExperienceAttendanceIntegrationTests extends IntegrationTestSupport {
  @Autowired private ExperienceAttendanceService attendances;

  // --- HTTP-level: today's actually-reachable behavior -----------------------------

  @Test
  void requiresAuthentication() throws Exception {
    assertThat(confirm(null, UUID.randomUUID().toString(), 1, "{}").statusCode()).isEqualTo(401);
  }

  @Test
  void rejectsPendingOnboarding() throws Exception {
    var signedIn = result(login());
    long userId = signedIn.path("userId").asLong();
    createdUsers.add(userId);
    String token = signedIn.path("accessToken").asText();
    assertThat(code(confirm(token, UUID.randomUUID().toString(), 1, "{}"))).isEqualTo("AUTH403_2");
  }

  @Test
  void requiresTheIdempotencyKeyHeader() throws Exception {
    String token = activeToken();
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/experience-reservations/1/attendance"))
            .timeout(java.time.Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"));
    assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(400);
  }

  @Test
  void rejectsMissingOrOthersReservation() throws Exception {
    String token = activeToken();
    assertThat(
            code(
                confirm(
                    token,
                    UUID.randomUUID().toString(),
                    9223372036854775807L,
                    attendanceBody(1L, "token"))))
        .isEqualTo("RESERVATION404_1");

    long otherOwner = otherUser();
    long reservationId = seedConfirmedReservation(experience(), otherOwner, null);
    assertThat(
            code(
                confirm(
                    token,
                    UUID.randomUUID().toString(),
                    reservationId,
                    attendanceBody(1L, "token"))))
        .isEqualTo("RESERVATION404_1");
  }

  @Test
  void rejectsWhenTheRequestBodyIsIncomplete() throws Exception {
    String token = activeToken();
    long userId = currentUserId();
    long reservationId = seedConfirmedReservation(experience(), userId, null);
    assertThat(
            code(
                confirm(
                    token,
                    UUID.randomUUID().toString(),
                    reservationId,
                    attendanceBody(null, "token"))))
        .isEqualTo("COMMON400_1");
    assertThat(
            code(
                confirm(
                    token, UUID.randomUUID().toString(), reservationId, attendanceBody(1L, null))))
        .isEqualTo("COMMON400_1");
    assertThat(
            code(
                confirm(
                    token, UUID.randomUUID().toString(), reservationId, attendanceBody(1L, ""))))
        .isEqualTo("COMMON400_1");
  }

  @Test
  void rejectsAReservationThatIsNotConfirmedYet() throws Exception {
    String token = activeToken();
    long userId = currentUserId();
    for (String status :
        new String[] {"REQUESTED", "REDIRECTED", "PENDING_VERIFICATION", "CANCELLED"}) {
      // A fresh experience per status: uq_experience_reservations_legacy_experience_user is
      // (experience_id, user_id) WHERE session_id IS NULL, and every row seeded here has no
      // session_id.
      long experienceId = experience();
      long reservationId =
          jdbc.queryForObject(
              "INSERT INTO experience_reservations(reservation_code,experience_id,user_id,"
                  + "reservation_status) VALUES (?,?,?,?) RETURNING reservation_id",
              Long.class,
              "attendance-test-" + UUID.randomUUID(),
              experienceId,
              userId,
              status);
      assertThat(
              code(
                  confirm(
                      token,
                      UUID.randomUUID().toString(),
                      reservationId,
                      attendanceBody(1L, "token"))))
          .as(status)
          .isEqualTo("ATTENDANCE409_1");
    }
  }

  @Test
  void aConfirmedReservationAlwaysFailsVerificationSinceNoProviderIntegrationExists()
      throws Exception {
    String token = activeToken();
    long userId = currentUserId();
    long hobbyId = hobby();
    long userHobbyId = userHobby(userId, hobbyId);
    long activityId = activity(stage(hobbyId));
    long experienceId = experience(hobbyId, activityId);
    long reservationId = seedConfirmedReservation(experienceId, userId, null);

    var response =
        confirm(
            token,
            UUID.randomUUID().toString(),
            reservationId,
            attendanceBody(userHobbyId, "any-token"));
    assertThat(response.statusCode()).isEqualTo(503);

    assertThat(code(response)).isEqualTo("ATTENDANCE503_1");
  }

  @Test
  void replaysTheExistingResultForTheSameReservationAndTreeWithoutReVerifying() throws Exception {
    String token = activeToken();
    long userId = currentUserId();
    long hobbyId = hobby();
    long userHobbyId = userHobby(userId, hobbyId);
    long activityId = activity(stage(hobbyId));
    long experienceId = experience(hobbyId, activityId);
    long reservationId = seedConfirmedReservation(experienceId, userId, null);
    long recordId =
        jdbc.queryForObject(
            "INSERT INTO activity_records(user_hobby_id,activity_id,activity_status,"
                + "growth_counted,growth_stage_number) VALUES (?,?,'COMPLETED',TRUE,0)"
                + " RETURNING activity_record_id",
            Long.class,
            userHobbyId,
            activityId);
    Instant verifiedAt = Instant.now().minusSeconds(60);
    jdbc.update(
        "UPDATE experience_reservations SET reservation_status='ATTENDED',"
            + " attendance_verified_at=?, credited_user_hobby_id=?, credited_activity_record_id=?"
            + " WHERE reservation_id=?",
        java.sql.Timestamp.from(verifiedAt),
        userHobbyId,
        recordId,
        reservationId);

    var response =
        confirm(
            token,
            UUID.randomUUID().toString(),
            reservationId,
            attendanceBody(userHobbyId, "any-token"));
    assertThat(response.statusCode()).isEqualTo(200);
    var result = result(response);
    assertThat(fieldNames(result))
        .containsExactlyInAnyOrder(
            "reservationId",
            "attendanceId",
            "attended",
            "verifiedAt",
            "recordId",
            "activityId",
            "userHobbyId",
            "growth");
    assertThat(result.path("reservationId").asLong()).isEqualTo(reservationId);
    assertThat(result.path("attended").asBoolean()).isTrue();
    assertThat(result.path("recordId").asLong()).isEqualTo(recordId);
    assertThat(result.path("activityId").asLong()).isEqualTo(activityId);
    assertThat(result.path("userHobbyId").asLong()).isEqualTo(userHobbyId);
    assertThat(result.path("growth").path("growthApplied").asBoolean()).isFalse();
  }

  @Test
  void rejectsAReplayForADifferentTreeThanTheOneAlreadyCredited() throws Exception {
    String token = activeToken();
    long userId = currentUserId();
    long hobbyId = hobby();
    long userHobbyId = userHobby(userId, hobbyId);
    long otherTreeId = userHobby(userId, hobby());
    long activityId = activity(stage(hobbyId));
    long experienceId = experience(hobbyId, activityId);
    long reservationId = seedConfirmedReservation(experienceId, userId, null);
    jdbc.update(
        "UPDATE experience_reservations SET reservation_status='ATTENDED',"
            + " attendance_verified_at=now(), credited_user_hobby_id=? WHERE reservation_id=?",
        userHobbyId,
        reservationId);

    assertThat(
            code(
                confirm(
                    token,
                    UUID.randomUUID().toString(),
                    reservationId,
                    attendanceBody(otherTreeId, "any-token"))))
        .isEqualTo("ATTENDANCE409_1");
  }

  // --- Service-level: business rules behind the always-failing token check ---------
  // applyAttendance is public specifically so these can call it directly, the same way
  // IdempotencyKeyServiceIntegrationTests exercises IdempotencyKeyService's own methods -
  // verifyAttendanceToken always throwing is what makes this the only way to reach them today.

  @Test
  void creditsANewActivityRecordAndAdvancesTheStageOnceTheThresholdIsMet() throws Exception {
    long userId = otherUser();
    long hobbyId = hobby();
    jdbc.update("UPDATE hobbies SET activities_per_stage=1 WHERE hobby_id=?", hobbyId);
    long userHobbyId = userHobby(userId, hobbyId);
    long activityId = activity(stage(hobbyId));
    long experienceId = experience(hobbyId, activityId);
    long reservationId = seedConfirmedReservation(experienceId, userId, null);

    var response = attendances.applyAttendance(userId, reservationId, userHobbyId);

    assertThat(response.attended()).isTrue();
    assertThat(response.activityId()).isEqualTo(activityId);
    assertThat(response.growth().growthApplied()).isTrue();
    assertThat(response.growth().distinctCompletedActivityCount()).isEqualTo(1);
    assertThat(response.growth().previousStageNumber()).isEqualTo(0);
    assertThat(response.growth().currentStageNumber()).isEqualTo(1);
    assertThat(response.growth().stageChanged()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM experience_reservations WHERE reservation_id=?",
                String.class,
                reservationId))
        .isEqualTo("ATTENDED");
    assertThat(
            jdbc.queryForObject(
                "SELECT current_stage_number FROM user_hobbies WHERE user_hobby_id=?",
                Short.class,
                userHobbyId))
        .isEqualTo((short) 1);
  }

  @Test
  void reusesAnInProgressSelfTrackedRecordWithoutResettingItsFields() throws Exception {
    long userId = otherUser();
    long hobbyId = hobby();
    long userHobbyId = userHobby(userId, hobbyId);
    long activityId = activity(stage(hobbyId));
    long experienceId = experience(hobbyId, activityId);
    long reservationId = seedConfirmedReservation(experienceId, userId, null);
    jdbc.update(
        "INSERT INTO activity_records(user_hobby_id,activity_id,activity_status,attempt_count,"
            + "step_progress) VALUES (?,?,'IN_PROGRESS',3,'{\"step1\":true}'::jsonb)",
        userHobbyId,
        activityId);
    var response = attendances.applyAttendance(userId, reservationId, userHobbyId);

    assertThat(response.growth().growthApplied()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT activity_status FROM activity_records WHERE user_hobby_id=? AND activity_id=?",
                String.class,
                userHobbyId,
                activityId))
        .isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT attempt_count FROM activity_records WHERE user_hobby_id=? AND activity_id=?",
                Integer.class,
                userHobbyId,
                activityId))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT step_progress::text FROM activity_records WHERE user_hobby_id=? AND activity_id=?",
                String.class,
                userHobbyId,
                activityId))
        .contains("step1");
  }

  @Test
  void doesNotDoubleCountGrowthAlreadyCreditedFromElsewhere() throws Exception {
    long userId = otherUser();
    long hobbyId = hobby();
    long userHobbyId = userHobby(userId, hobbyId);
    long activityId = activity(stage(hobbyId));
    long experienceId = experience(hobbyId, activityId);
    long reservationId = seedConfirmedReservation(experienceId, userId, null);
    jdbc.update(
        "INSERT INTO activity_records(user_hobby_id,activity_id,activity_status,growth_counted,"
            + "growth_stage_number) VALUES (?,?,'COMPLETED',TRUE,0)",
        userHobbyId,
        activityId);
    var response = attendances.applyAttendance(userId, reservationId, userHobbyId);

    assertThat(response.growth().growthApplied()).isFalse();
    assertThat(response.growth().distinctCompletedActivityCount()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT credited_activity_record_id FROM experience_reservations WHERE reservation_id=?",
                Long.class,
                reservationId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT credited_user_hobby_id FROM experience_reservations WHERE reservation_id=?",
                Long.class,
                reservationId))
        .isEqualTo(userHobbyId);
  }

  @Test
  void rejectsAHobbyMismatchBetweenTheExperienceAndTheTree() throws Exception {
    long userId = otherUser();
    long experienceHobbyId = hobby();
    long treeHobbyId = hobby();
    long userHobbyId = userHobby(userId, treeHobbyId);
    long activityId = activity(stage(experienceHobbyId));
    long experienceId = experience(experienceHobbyId, activityId);
    long reservationId = seedConfirmedReservation(experienceId, userId, null);
    assertThatThrownBy(() -> attendances.applyAttendance(userId, reservationId, userHobbyId))
        .isInstanceOf(ProjectException.class)
        .extracting(e -> ((ProjectException) e).getErrorCode().getCode())
        .isEqualTo("ATTENDANCE400_1");
  }

  @Test
  void rejectsATreeThatIsNotOwnedOrDoesNotExist() throws Exception {
    long userId = otherUser();
    long hobbyId = hobby();
    long activityId = activity(stage(hobbyId));
    long experienceId = experience(hobbyId, activityId);
    long reservationId = seedConfirmedReservation(experienceId, userId, null);
    assertThatThrownBy(
            () -> attendances.applyAttendance(userId, reservationId, 9223372036854775807L))
        .isInstanceOf(ProjectException.class)
        .extracting(e -> ((ProjectException) e).getErrorCode().getCode())
        .isEqualTo("TREE404_1");
  }

  @Test
  void rejectsATreeThatIsNotActive() throws Exception {
    long userId = otherUser();
    long hobbyId = hobby();
    long userHobbyId = userHobby(userId, hobbyId);
    jdbc.update("UPDATE user_hobbies SET tree_status='PAUSED' WHERE user_hobby_id=?", userHobbyId);
    long activityId = activity(stage(hobbyId));
    long experienceId = experience(hobbyId, activityId);
    long reservationId = seedConfirmedReservation(experienceId, userId, null);
    assertThatThrownBy(() -> attendances.applyAttendance(userId, reservationId, userHobbyId))
        .isInstanceOf(ProjectException.class)
        .extracting(e -> ((ProjectException) e).getErrorCode().getCode())
        .isEqualTo("TREE409_4");
  }

  @Test
  void rejectsAClassWithNoActivityMappingAndLeavesNoPartialState() throws Exception {
    long userId = otherUser();
    long hobbyId = hobby();
    long userHobbyId = userHobby(userId, hobbyId);
    long experienceId = experience(hobbyId, null);
    long reservationId = seedConfirmedReservation(experienceId, userId, null);
    assertThatThrownBy(() -> attendances.applyAttendance(userId, reservationId, userHobbyId))
        .isInstanceOf(ProjectException.class)
        .extracting(e -> ((ProjectException) e).getErrorCode().getCode())
        .isEqualTo("ATTENDANCE422_1");

    // A rejection this late (the last check before any mutation) must still leave nothing
    // behind - the whole method is one transaction, so a thrown exception rolls everything
    // back together: reservation status, growth, and the tree's stage all stay untouched.
    assertThat(
            jdbc.queryForObject(
                "SELECT reservation_status FROM experience_reservations WHERE reservation_id=?",
                String.class,
                reservationId))
        .isEqualTo("CONFIRMED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_records WHERE user_hobby_id=?",
                Integer.class,
                userHobbyId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT current_stage_number FROM user_hobbies WHERE user_hobby_id=?",
                Short.class,
                userHobbyId))
        .isEqualTo((short) 0);
  }

  @Test
  void concurrentAttendanceConfirmationsForTheSameTreeAndActivityCreditGrowthOnlyOnce()
      throws Exception {
    long userId = otherUser();
    long hobbyId = hobby();
    long userHobbyId = userHobby(userId, hobbyId);
    long activityId = activity(stage(hobbyId));
    long reservationA = seedConfirmedReservation(experience(hobbyId, activityId), userId, null);
    long reservationB = seedConfirmedReservation(experience(hobbyId, activityId), userId, null);

    var barrier = new java.util.concurrent.CyclicBarrier(2);
    List<ConfirmAttendanceResponse> responses;
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var futureA =
          executor.submit(
              () -> {
                barrier.await();
                return attendances.applyAttendance(userId, reservationA, userHobbyId);
              });
      var futureB =
          executor.submit(
              () -> {
                barrier.await();
                return attendances.applyAttendance(userId, reservationB, userHobbyId);
              });
      responses =
          List.of(
              futureA.get(15, java.util.concurrent.TimeUnit.SECONDS),
              futureB.get(15, java.util.concurrent.TimeUnit.SECONDS));
    }

    assertThat(responses.stream().map(r -> r.growth().growthApplied()).toList())
        .containsExactlyInAnyOrder(true, false);
    assertThat(responses.stream().map(ConfirmAttendanceResponse::recordId).distinct().toList())
        .hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM activity_records WHERE user_hobby_id=? AND activity_id=?",
                Integer.class,
                userHobbyId,
                activityId))
        .isEqualTo(1);
  }

  // --- fixtures ----------------------------------------------------------------------

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

  long otherUser() {
    long id =
        jdbc.queryForObject(
            "INSERT INTO users(provider,provider_user_id,account_status) VALUES"
                + " ('KAKAO',?,'ACTIVE') RETURNING user_id",
            Long.class,
            "attendance-test-" + UUID.randomUUID());
    createdUsers.add(id);
    return id;
  }

  long hobby() {
    return jdbc.queryForObject(
        "INSERT INTO hobbies(hobby_code,hobby_name) VALUES (?,'참여 확인 테스트') RETURNING hobby_id",
        Long.class,
        "attendance-test-" + UUID.randomUUID().toString().substring(0, 12));
  }

  long stage(long hobbyId) {
    return jdbc.queryForObject(
        "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,0,'씨앗')"
            + " RETURNING hobby_stage_id",
        Long.class,
        hobbyId);
  }

  long activity(long stageId) {
    return jdbc.queryForObject(
        "INSERT INTO activities(hobby_stage_id,activity_code,activity_name) VALUES (?,?,'참여 확인 테스트 활동')"
            + " RETURNING activity_id",
        Long.class,
        stageId,
        "attendance-test-" + UUID.randomUUID());
  }

  long userHobby(long userId, long hobbyId) {
    return jdbc.queryForObject(
        "INSERT INTO user_hobbies(user_id,hobby_id,tree_status) VALUES (?,?,'ACTIVE')"
            + " RETURNING user_hobby_id",
        Long.class,
        userId,
        hobbyId);
  }

  long experience() {
    return experience(null, null);
  }

  long experience(Long hobbyId, Long linkedActivityId) {
    return jdbc.queryForObject(
        "INSERT INTO experiences(experience_type,title,listing_status,hobby_id,"
            + "linked_activity_id) VALUES ('CLASS',?,'OPEN',?,?) RETURNING experience_id",
        Long.class,
        "attendance-test-" + UUID.randomUUID(),
        hobbyId,
        linkedActivityId);
  }

  long seedConfirmedReservation(long experienceId, long userId, Long providerId) {
    return jdbc.queryForObject(
        "INSERT INTO experience_reservations(reservation_code,experience_id,user_id,"
            + "provider_id,reservation_status) VALUES (?,?,?,?,'CONFIRMED') RETURNING reservation_id",
        Long.class,
        "attendance-test-" + UUID.randomUUID(),
        experienceId,
        userId,
        providerId);
  }

  String attendanceBody(Long userHobbyId, String attendanceToken) {
    StringBuilder sb = new StringBuilder("{");
    if (userHobbyId != null) sb.append("\"userHobbyId\":").append(userHobbyId);
    if (attendanceToken != null)
      sb.append(sb.length() > 1 ? "," : "")
          .append("\"attendanceToken\":\"")
          .append(attendanceToken)
          .append('"');
    sb.append("}");
    return sb.toString();
  }

  HttpResponse<String> confirm(String token, String idempotencyKey, long reservationId, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/v1/experience-reservations/"
                        + reservationId
                        + "/attendance"))
            .timeout(java.time.Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  String code(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("code").asText();
  }

  java.util.List<String> fieldNames(JsonNode node) {
    return new java.util.ArrayList<>(node.propertyNames());
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update(
        "DELETE FROM experience_reservations WHERE experience_id IN"
            + " (SELECT experience_id FROM experiences WHERE title LIKE 'attendance-test-%')");
    jdbc.update("DELETE FROM experiences WHERE title LIKE 'attendance-test-%'");
    jdbc.update(
        "DELETE FROM user_hobbies WHERE hobby_id IN"
            + " (SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'attendance-test-%')");
    jdbc.update(
        "DELETE FROM activities WHERE hobby_stage_id IN"
            + " (SELECT hobby_stage_id FROM hobby_stages hs"
            + "  JOIN hobbies h ON h.hobby_id = hs.hobby_id"
            + "  WHERE h.hobby_code LIKE 'attendance-test-%')");
    jdbc.update(
        "DELETE FROM hobby_stages WHERE hobby_id IN"
            + " (SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'attendance-test-%')");
    jdbc.update("DELETE FROM hobbies WHERE hobby_code LIKE 'attendance-test-%'");
    super.cleanupSeededHobbies(userId);
  }
}
