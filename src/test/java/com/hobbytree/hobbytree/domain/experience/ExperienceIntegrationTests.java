package com.hobbytree.hobbytree.domain.experience;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class ExperienceIntegrationTests extends IntegrationTestSupport {
  private static final String PATH = "/api/v1/experiences";
  private final List<Long> createdExperienceIds = new java.util.ArrayList<>();

  long experience(String listingStatus) {
    return experience("CLASS", listingStatus);
  }

  long experience(String experienceType, String listingStatus) {
    long id =
        jdbc.queryForObject(
            "INSERT INTO experiences(experience_type,title,listing_status) VALUES (?,?,?)"
                + " RETURNING experience_id",
            Long.class,
            experienceType,
            "class-open-test-" + UUID.randomUUID(),
            listingStatus);
    createdExperienceIds.add(id);
    return id;
  }

  long listedExperience(
      String experienceType,
      String listingStatus,
      Long hobbyId,
      Long regionId,
      Long providerId,
      Integer price) {
    long id =
        jdbc.queryForObject(
            "INSERT INTO experiences(experience_type,title,listing_status,hobby_id,region_id,"
                + "provider_id,price_amount) VALUES (?,?,?,?,?,?,?) RETURNING experience_id",
            Long.class,
            experienceType,
            "experience-list-test-" + UUID.randomUUID(),
            listingStatus,
            hobbyId,
            regionId,
            providerId,
            price);
    createdExperienceIds.add(id);
    return id;
  }

  void session(long experienceId, Instant startsAt, int durationMinutes) {
    jdbc.update(
        "INSERT INTO experience_sessions(experience_id,starts_at,duration_minutes) VALUES (?,?,?)",
        experienceId,
        java.sql.Timestamp.from(startsAt),
        durationMinutes);
  }

  void session(long experienceId, Instant startsAt, int durationMinutes, Integer capacity) {
    jdbc.update(
        "INSERT INTO experience_sessions(experience_id,starts_at,duration_minutes,capacity)"
            + " VALUES (?,?,?,?)",
        experienceId,
        java.sql.Timestamp.from(startsAt),
        durationMinutes,
        capacity);
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
        "INSERT INTO activities(hobby_stage_id,activity_code,activity_name) VALUES (?,?,'테스트 활동')"
            + " RETURNING activity_id",
        Long.class,
        stageId,
        "experience-list-test-" + UUID.randomUUID());
  }

  long provider(String name) {
    return jdbc.queryForObject(
        "INSERT INTO experience_providers(name) VALUES (?) RETURNING provider_id",
        Long.class,
        name);
  }

  long hobby() {
    return jdbc.queryForObject(
        "INSERT INTO hobbies(hobby_code,hobby_name) VALUES (?,'체험 목록 테스트') RETURNING hobby_id",
        Long.class,
        "experience-list-test-" + UUID.randomUUID().toString().substring(0, 12));
  }

  long detailExperience(
      Long hobbyId,
      Long regionId,
      Long providerId,
      Integer price,
      String description,
      String address,
      String venueName,
      String materialsJson,
      String externalUrl,
      String cancellationPolicyUrl,
      Long linkedActivityId) {
    long id =
        jdbc.queryForObject(
            "INSERT INTO experiences(experience_type,title,listing_status,hobby_id,region_id,"
                + "provider_id,price_amount,description,venue_address,venue_name,materials,"
                + "external_url,cancellation_policy_url,linked_activity_id) VALUES"
                + " ('CLASS',?,'OPEN',?,?,?,?,?,?,?,?::jsonb,?,?,?) RETURNING experience_id",
            Long.class,
            "experience-list-test-" + UUID.randomUUID(),
            hobbyId,
            regionId,
            providerId,
            price,
            description,
            address,
            venueName,
            materialsJson,
            externalUrl,
            cancellationPolicyUrl,
            linkedActivityId);
    createdExperienceIds.add(id);
    return id;
  }

  long anyRegionId() {
    return jdbc.queryForObject(
        "SELECT region_id FROM regions WHERE level='SIGUNGU' ORDER BY region_id LIMIT 1",
        Long.class);
  }

  String regionFullName(long regionId) {
    return jdbc.queryForObject(
        "SELECT full_name FROM regions WHERE region_id=?", String.class, regionId);
  }

  HttpResponse<String> list(String token, String query) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + query))
            .timeout(java.time.Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> detail(String token, long experienceId) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + "/" + experienceId))
            .timeout(java.time.Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  String code(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("code").asText();
  }

  HttpResponse<String> reserve(
      String token, String idempotencyKey, long experienceId, String requestBody) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + PATH + "/" + experienceId + "/reservations"))
            .timeout(java.time.Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  String reservationBody(Long sessionId, Long userHobbyId, String externalReservationId) {
    StringBuilder sb = new StringBuilder("{");
    if (sessionId != null) sb.append("\"sessionId\":").append(sessionId);
    if (userHobbyId != null)
      sb.append(sb.length() > 1 ? "," : "").append("\"userHobbyId\":").append(userHobbyId);
    if (externalReservationId != null)
      sb.append(sb.length() > 1 ? "," : "")
          .append("\"externalReservationId\":\"")
          .append(externalReservationId)
          .append('"');
    sb.append("}");
    return sb.toString();
  }

  HttpResponse<String> myReservations(String token, String query) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/v1/users/me/experience-reservations"
                        + query))
            .timeout(java.time.Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  long otherUser() {
    long id =
        jdbc.queryForObject(
            "INSERT INTO users(provider,provider_user_id,account_status) VALUES"
                + " ('KAKAO',?,'ACTIVE') RETURNING user_id",
            Long.class,
            "experience-list-test-" + UUID.randomUUID());
    createdUsers.add(id);
    return id;
  }

  long userHobby(long userId, long hobbyId) {
    return jdbc.queryForObject(
        "INSERT INTO user_hobbies(user_id,hobby_id,tree_status) VALUES (?,?,'ACTIVE')"
            + " RETURNING user_hobby_id",
        Long.class,
        userId,
        hobbyId);
  }

  // Seeds a reservation directly, bypassing the real endpoint - used only to occupy a seat so
  // a second, real request can observe the session as full without needing a second real
  // authenticated caller.
  void seedReservation(long sessionId, long experienceId, long userId, Long providerId) {
    seedReservation(sessionId, experienceId, userId, providerId, "REDIRECTED");
  }

  void seedReservation(
      long sessionId, long experienceId, long userId, Long providerId, String status) {
    jdbc.update(
        "INSERT INTO experience_reservations(reservation_code,experience_id,session_id,user_id,"
            + "provider_id,reservation_status) VALUES (?,?,?,?,?,?)",
        "RSV-" + UUID.randomUUID(),
        experienceId,
        sessionId,
        userId,
        providerId,
        status);
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    // experiences isn't owned by a user (no cascade from deleting the user), so it must be
    // cleaned up explicitly; a title-prefix match is simpler than tracking each seeded id.
    // Legacy rows may have no session, so session deletion alone cannot clean them up.
    jdbc.update(
        "DELETE FROM experience_reservations WHERE experience_id IN"
            + " (SELECT experience_id FROM experiences WHERE title LIKE 'class-open-test-%'"
            + " OR title LIKE 'experience-list-test-%')");
    jdbc.update(
        "DELETE FROM experience_sessions WHERE experience_id IN"
            + " (SELECT experience_id FROM experiences WHERE title LIKE 'class-open-test-%'"
            + " OR title LIKE 'experience-list-test-%')");
    jdbc.update(
        "DELETE FROM experiences WHERE title LIKE 'class-open-test-%'"
            + " OR title LIKE 'experience-list-test-%'");
    jdbc.update(
        "DELETE FROM activities WHERE hobby_stage_id IN"
            + " (SELECT hobby_stage_id FROM hobby_stages hs"
            + "  JOIN hobbies h ON h.hobby_id = hs.hobby_id"
            + "  WHERE h.hobby_code LIKE 'experience-list-test-%')");
    jdbc.update(
        "DELETE FROM hobby_stages WHERE hobby_id IN"
            + " (SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'experience-list-test-%')");
    // user_hobbies.hobby_id has no cascade from hobbies - must clear it before the hobby
    // delete below, same reasoning as the base class's own cleanupSeededHobbies.
    jdbc.update(
        "DELETE FROM user_hobbies WHERE hobby_id IN"
            + " (SELECT hobby_id FROM hobbies WHERE hobby_code LIKE 'experience-list-test-%')");
    jdbc.update("DELETE FROM hobbies WHERE hobby_code LIKE 'experience-list-test-%'");
    jdbc.update("DELETE FROM experience_providers WHERE name LIKE 'experience-list-test-%'");
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
  void rejectsComingSoonMeetupOrExternalExperiences() throws Exception {
    String token = activeToken();
    for (String type : List.of("MEETUP", "EXTERNAL")) {
      long experienceId = experience(type, "COMING_SOON");
      var response = subscribeToClassOpen(token, experienceId);
      assertThat(response.statusCode()).isEqualTo(409);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .isEqualTo("SUBSCRIPTION409_1");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM notification_subscriptions"
                      + " WHERE experience_id=? AND subscription_type='CLASS_OPEN'",
                  Integer.class,
                  experienceId))
          .isZero();
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

  // --- API-35: 클래스·모임 목록 ---------------------------------------------

  @Test
  void returnsVisibleExperiencesWithTheExactContractAndNextSession() throws Exception {
    String token = activeToken();
    long hobbyId = hobby();
    long regionId = anyRegionId();
    long providerId = provider("experience-list-test-" + UUID.randomUUID());
    long experienceId = listedExperience("CLASS", "OPEN", hobbyId, regionId, providerId, 25000);
    Instant next = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    session(experienceId, next, 60);
    session(experienceId, next.plus(7, ChronoUnit.DAYS), 60);

    var response = list(token, "?hobbyId=" + hobbyId);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(code(response)).isEqualTo("EXPERIENCE200_1");
    var result = result(response);
    assertThat(fieldNames(result)).containsExactlyInAnyOrder("items", "nextCursor", "hasNext");
    assertThat(result.path("items")).hasSize(1);

    var item = result.path("items").get(0);
    assertThat(fieldNames(item))
        .containsExactlyInAnyOrder(
            "experienceId",
            "type",
            "title",
            "hobbyId",
            "hobbyName",
            "thumbnailUrl",
            "region",
            "provider",
            "price",
            "currency",
            "status",
            "nextSession",
            "providerUpdatedAt");
    assertThat(item.path("experienceId").asLong()).isEqualTo(experienceId);
    assertThat(item.path("type").asText()).isEqualTo("CLASS");
    assertThat(item.path("hobbyId").asLong()).isEqualTo(hobbyId);
    assertThat(item.path("hobbyName").asText()).isEqualTo("체험 목록 테스트");
    assertThat(item.path("region").path("regionId").asLong()).isEqualTo(regionId);
    assertThat(item.path("region").path("name").asText()).isEqualTo(regionFullName(regionId));
    assertThat(item.path("provider").path("providerId").asLong()).isEqualTo(providerId);
    assertThat(item.path("price").asInt()).isEqualTo(25000);
    assertThat(item.path("currency").asText()).isEqualTo("KRW");
    assertThat(item.path("status").asText()).isEqualTo("OPEN");
    assertThat(item.path("nextSession").path("startsAt").asText()).isEqualTo(next.toString());
    assertThat(item.path("nextSession").path("durationMinutes").asInt()).isEqualTo(60);
  }

  @Test
  void excludesDraftAndCancelledExperiences() throws Exception {
    String token = activeToken();
    long hobbyId = hobby();
    for (String status : List.of("DRAFT", "CANCELLED")) {
      long id = listedExperience("CLASS", status, hobbyId, null, null, null);
      session(id, Instant.now().plus(1, ChronoUnit.DAYS), 30);
    }
    long visible = listedExperience("CLASS", "OPEN", hobbyId, null, null, null);
    session(visible, Instant.now().plus(1, ChronoUnit.DAYS), 30);

    var result = result(list(token, "?hobbyId=" + hobbyId));
    assertThat(result.path("items")).hasSize(1);
    assertThat(result.path("items").get(0).path("experienceId").asLong()).isEqualTo(visible);
  }

  // type=EXTERNAL is never a valid filter value (rejected as EXPERIENCE400_1 elsewhere), but
  // an EXTERNAL-type listing must also stay out of the catalog when no type filter is given at
  // all - it's not one of the two browsable types (CLASS/MEETUP), regardless of its own status.
  @Test
  void excludesExternalTypeExperiencesEvenWithoutATypeFilter() throws Exception {
    String token = activeToken();
    long hobbyId = hobby();
    long external = listedExperience("EXTERNAL", "OPEN", hobbyId, null, null, null);
    session(external, Instant.now().plus(1, ChronoUnit.DAYS), 30);
    long visible = listedExperience("CLASS", "OPEN", hobbyId, null, null, null);
    session(visible, Instant.now().plus(1, ChronoUnit.DAYS), 30);

    var result = result(list(token, "?hobbyId=" + hobbyId));
    assertThat(result.path("items")).hasSize(1);
    assertThat(result.path("items").get(0).path("experienceId").asLong()).isEqualTo(visible);
  }

  @Test
  void filtersByTypeAndStatus() throws Exception {
    String token = activeToken();
    long hobbyId = hobby();
    long meetup = listedExperience("MEETUP", "OPEN", hobbyId, null, null, 1000);
    session(meetup, Instant.now().plus(1, ChronoUnit.DAYS), 30);
    long comingSoonClass = listedExperience("CLASS", "COMING_SOON", hobbyId, null, null, 2000);
    session(comingSoonClass, Instant.now().plus(1, ChronoUnit.DAYS), 30);

    var byType = result(list(token, "?hobbyId=" + hobbyId + "&type=MEETUP"));
    assertThat(byType.path("items")).hasSize(1);
    assertThat(byType.path("items").get(0).path("experienceId").asLong()).isEqualTo(meetup);

    var byStatus = result(list(token, "?hobbyId=" + hobbyId + "&status=COMING_SOON"));
    assertThat(byStatus.path("items")).hasSize(1);
    assertThat(byStatus.path("items").get(0).path("experienceId").asLong())
        .isEqualTo(comingSoonClass);
  }

  @Test
  void sortsByPriceAscending() throws Exception {
    String token = activeToken();
    long hobbyId = hobby();
    long cheap = listedExperience("CLASS", "OPEN", hobbyId, null, null, 1000);
    session(cheap, Instant.now().plus(1, ChronoUnit.DAYS), 30);
    long expensive = listedExperience("CLASS", "OPEN", hobbyId, null, null, 5000);
    session(expensive, Instant.now().plus(1, ChronoUnit.DAYS), 30);

    var result = result(list(token, "?hobbyId=" + hobbyId + "&sort=PRICE_ASC"));
    var ids =
        toList(result.path("items")).stream()
            .map(n -> n.path("experienceId").asLong())
            .collect(Collectors.toList());
    assertThat(ids).containsExactly(cheap, expensive);
  }

  @Test
  void excludesExperiencesWithoutASessionInTheRequestedDateRange() throws Exception {
    String token = activeToken();
    long hobbyId = hobby();
    long inRange = listedExperience("CLASS", "OPEN", hobbyId, null, null, null);
    session(inRange, Instant.now().plus(2, ChronoUnit.DAYS), 30);
    long outOfRange = listedExperience("CLASS", "OPEN", hobbyId, null, null, null);
    session(outOfRange, Instant.now().plus(30, ChronoUnit.DAYS), 30);

    String from = java.time.LocalDate.now().toString();
    String to = java.time.LocalDate.now().plusDays(5).toString();
    var result = result(list(token, "?hobbyId=" + hobbyId + "&fromDate=" + from + "&toDate=" + to));
    assertThat(result.path("items")).hasSize(1);
    assertThat(result.path("items").get(0).path("experienceId").asLong()).isEqualTo(inRange);
  }

  @Test
  void paginatesWithACursor() throws Exception {
    String token = activeToken();
    long hobbyId = hobby();
    long first = listedExperience("CLASS", "OPEN", hobbyId, null, null, null);
    session(first, Instant.now().plus(1, ChronoUnit.DAYS), 30);
    long second = listedExperience("CLASS", "OPEN", hobbyId, null, null, null);
    session(second, Instant.now().plus(2, ChronoUnit.DAYS), 30);

    var page1 = result(list(token, "?hobbyId=" + hobbyId + "&size=1"));
    assertThat(page1.path("items")).hasSize(1);
    assertThat(page1.path("hasNext").asBoolean()).isTrue();
    assertThat(page1.path("items").get(0).path("experienceId").asLong()).isEqualTo(first);
    String cursor = page1.path("nextCursor").asText();

    var page2 =
        result(
            list(
                token,
                "?hobbyId="
                    + hobbyId
                    + "&size=1&cursor="
                    + java.net.URLEncoder.encode(cursor, "UTF-8")));
    assertThat(page2.path("items")).hasSize(1);
    assertThat(page2.path("hasNext").asBoolean()).isFalse();
    assertThat(page2.path("items").get(0).path("experienceId").asLong()).isEqualTo(second);
  }

  @Test
  void rejectsInvalidFiltersAndCursor() throws Exception {
    String token = activeToken();
    for (String query :
        List.of(
            "?type=EXTERNAL",
            "?status=DRAFT",
            "?sort=INVALID",
            "?fromDate=not-a-date",
            "?fromDate=2026-11-10&toDate=2026-11-01",
            "?size=0",
            "?size=51",
            "?cursor=not-base64!!!")) {
      var response = list(token, query);
      assertThat(response.statusCode()).as(query).isEqualTo(400);
      assertThat(code(response)).as(query).isEqualTo("EXPERIENCE400_1");
    }
  }

  @Test
  void listRejectsPendingOnboarding() throws Exception {
    var signedIn = result(login());
    long userId = signedIn.path("userId").asLong();
    createdUsers.add(userId);
    var response = list(signedIn.path("accessToken").asText(), "");
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(code(response)).isEqualTo("AUTH403_2");
  }

  @Test
  void listRequiresAuthentication() throws Exception {
    assertThat(list(null, "").statusCode()).isEqualTo(401);
  }

  // --- API-36: 클래스·모임 상세 ---------------------------------------------

  @Test
  void returnsExperienceDetailWithTheExactContract() throws Exception {
    String token = activeToken();
    long hobbyId = hobby();
    long regionId = anyRegionId();
    long providerId = provider("experience-list-test-" + UUID.randomUUID());
    long stageId = stage(hobbyId);
    long activityId = activity(stageId);
    long experienceId =
        detailExperience(
            hobbyId,
            regionId,
            providerId,
            25000,
            "기초 명암을 배우며 작은 그림 한 장을 완성해요.",
            "서울특별시 종로구 예시로 1",
            "예시 취미공방",
            "[\"연필과 종이는 현장에서 제공\"]",
            "https://partner.example.com/classes/601",
            "https://partner.example.com/policies/cancellation",
            activityId);
    Instant next = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    session(experienceId, next, 60, 2);
    // subscribeToClassOpen only succeeds while listingStatus='COMING_SOON', but this fixture is
    // 'OPEN' (to exercise the rest of detail()'s fields) - seed the subscription row directly
    // instead of going through that unrelated business rule.
    long subscriberUserId =
        jdbc.queryForObject(
            "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);
    jdbc.update(
        "INSERT INTO notification_subscriptions(user_id,subscription_type,experience_id)"
            + " VALUES (?,'CLASS_OPEN',?)",
        subscriberUserId,
        experienceId);

    var response = detail(token, experienceId);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(code(response)).isEqualTo("EXPERIENCE200_2");
    var result = result(response);

    assertThat(fieldNames(result))
        .containsExactlyInAnyOrder(
            "experienceId",
            "type",
            "title",
            "hobbyId",
            "hobbyName",
            "thumbnailUrl",
            "region",
            "provider",
            "price",
            "currency",
            "status",
            "nextSession",
            "providerUpdatedAt",
            "description",
            "address",
            "venueName",
            "materials",
            "sessions",
            "bookingUrl",
            "cancellationPolicyUrl",
            "activityRecognition",
            "isOpenNotificationSubscribed");
    assertThat(result.path("experienceId").asLong()).isEqualTo(experienceId);
    assertThat(result.path("hobbyId").asLong()).isEqualTo(hobbyId);
    assertThat(result.path("region").path("regionId").asLong()).isEqualTo(regionId);
    assertThat(result.path("provider").path("providerId").asLong()).isEqualTo(providerId);
    assertThat(result.path("address").asText()).isEqualTo("서울특별시 종로구 예시로 1");
    assertThat(result.path("venueName").asText()).isEqualTo("예시 취미공방");
    var materials = toList(result.path("materials"));
    assertThat(materials).hasSize(1);
    assertThat(materials.get(0).asText()).isEqualTo("연필과 종이는 현장에서 제공");
    assertThat(result.path("bookingUrl").asText())
        .isEqualTo("https://partner.example.com/classes/601");
    assertThat(result.path("cancellationPolicyUrl").asText())
        .isEqualTo("https://partner.example.com/policies/cancellation");

    assertThat(result.path("sessions")).hasSize(1);
    var session = result.path("sessions").get(0);
    assertThat(fieldNames(session))
        .containsExactlyInAnyOrder("sessionId", "startsAt", "endsAt", "status", "remainingSeats");
    assertThat(session.path("startsAt").asText()).isEqualTo(next.toString());
    assertThat(session.path("endsAt").asText())
        .isEqualTo(next.plus(1, ChronoUnit.HOURS).toString());
    assertThat(session.path("status").asText()).isEqualTo("OPEN");
    assertThat(session.path("remainingSeats").asInt()).isEqualTo(2);

    assertThat(result.path("activityRecognition").path("supported").asBoolean()).isTrue();
    assertThat(result.path("activityRecognition").path("activityId").asLong())
        .isEqualTo(activityId);
    assertThat(result.path("activityRecognition").path("verificationRequired").asBoolean())
        .isTrue();
    assertThat(result.path("isOpenNotificationSubscribed").asBoolean()).isTrue();
  }

  @Test
  void returnsFalseActivityRecognitionAndSubscriptionWhenNeitherIsSet() throws Exception {
    String token = activeToken();
    long experienceId =
        detailExperience(null, null, null, null, null, null, null, "[]", null, null, null);
    session(experienceId, Instant.now().plus(1, ChronoUnit.DAYS), 30, null);

    var result = result(detail(token, experienceId));
    assertThat(result.path("activityRecognition").path("supported").asBoolean()).isFalse();
    assertThat(result.path("activityRecognition").path("activityId").isNull()).isTrue();
    assertThat(result.path("activityRecognition").path("verificationRequired").asBoolean())
        .isFalse();
    assertThat(result.path("isOpenNotificationSubscribed").asBoolean()).isFalse();
    assertThat(result.path("sessions").get(0).path("remainingSeats").isNull()).isTrue();
  }

  @Test
  void onlyShowsUpcomingSessionsInDetail() throws Exception {
    String token = activeToken();
    long experienceId =
        detailExperience(null, null, null, null, null, null, null, "[]", null, null, null);
    session(experienceId, Instant.now().minus(1, ChronoUnit.DAYS), 30, null);
    Instant upcoming = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    session(experienceId, upcoming, 30, null);

    var result = result(detail(token, experienceId));
    assertThat(result.path("sessions")).hasSize(1);
    assertThat(result.path("sessions").get(0).path("startsAt").asText())
        .isEqualTo(upcoming.toString());
    assertThat(result.path("nextSession").path("startsAt").asText()).isEqualTo(upcoming.toString());
  }

  // Line 253's CHECK(capacity > 0) means remainingSeats alone can never drive a session to
  // CLOSED - this is what actually exercises that branch.
  @Test
  void marksSessionsAsClosedWhenTheExperienceItselfIsNotOpen() throws Exception {
    String token = activeToken();
    for (String listingStatus : List.of("COMING_SOON", "CLOSED")) {
      long experienceId =
          detailExperience(null, null, null, null, null, null, null, "[]", null, null, null);
      jdbc.update(
          "UPDATE experiences SET listing_status=? WHERE experience_id=?",
          listingStatus,
          experienceId);
      session(experienceId, Instant.now().plus(1, ChronoUnit.DAYS), 30, 5);

      var result = result(detail(token, experienceId));
      assertThat(result.path("sessions").get(0).path("status").asText())
          .as(listingStatus)
          .isEqualTo("CLOSED");
      assertThat(result.path("sessions").get(0).path("remainingSeats").asInt())
          .as(listingStatus)
          .isEqualTo(5);
    }
  }

  @Test
  void remainingSeatsReflectsActiveReservationsAndExcludesCancelled() throws Exception {
    String token = activeToken();
    long experienceId =
        detailExperience(null, null, null, null, null, null, null, "[]", null, null, null);
    session(experienceId, Instant.now().plus(1, ChronoUnit.DAYS), 30, 3);
    long sessionId =
        jdbc.queryForObject(
            "SELECT session_id FROM experience_sessions WHERE experience_id=?",
            Long.class,
            experienceId);

    seedReservation(sessionId, experienceId, otherUser(), null, "CANCELLED");
    var afterCancelledOnly = result(detail(token, experienceId));
    assertThat(afterCancelledOnly.path("sessions").get(0).path("remainingSeats").asInt())
        .isEqualTo(3);
    assertThat(afterCancelledOnly.path("sessions").get(0).path("status").asText())
        .isEqualTo("OPEN");

    seedReservation(sessionId, experienceId, otherUser(), null, "REDIRECTED");
    var afterOneActive = result(detail(token, experienceId));
    assertThat(afterOneActive.path("sessions").get(0).path("remainingSeats").asInt()).isEqualTo(2);

    seedReservation(sessionId, experienceId, otherUser(), null, "REDIRECTED");
    seedReservation(sessionId, experienceId, otherUser(), null, "PENDING_VERIFICATION");
    var afterFull = result(detail(token, experienceId));
    assertThat(afterFull.path("sessions").get(0).path("remainingSeats").asInt()).isEqualTo(0);
    assertThat(afterFull.path("sessions").get(0).path("status").asText()).isEqualTo("CLOSED");
  }

  @Test
  void detailShowsClosedWhenAnUnresolvedLegacyReservationBlocksAllNewBookings() throws Exception {
    String token = activeToken();
    long experienceId = experience("OPEN");
    session(experienceId, Instant.now().plus(1, ChronoUnit.DAYS), 30, 5);
    jdbc.update(
        "INSERT INTO experience_reservations(reservation_code,experience_id,user_id,"
            + "reservation_status) VALUES (?,?,?,'REQUESTED')",
        "legacy-" + UUID.randomUUID(),
        experienceId,
        otherUser());

    var result = result(detail(token, experienceId));
    assertThat(result.path("sessions").get(0).path("status").asText()).isEqualTo("CLOSED");
    assertThat(result.path("sessions").get(0).path("remainingSeats").asInt()).isEqualTo(5);
  }

  @Test
  void returnsNotFoundForHiddenOrMissingExperience() throws Exception {
    String token = activeToken();
    for (String status : List.of("DRAFT", "CANCELLED")) {
      long id = experience(status);
      var response = detail(token, id);
      assertThat(response.statusCode()).as(status).isEqualTo(404);
      assertThat(code(response)).as(status).isEqualTo("EXPERIENCE404_1");
    }
    var missing = detail(token, 9223372036854775807L);
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(code(missing)).isEqualTo("EXPERIENCE404_1");
  }

  @Test
  void excludesExternalTypeFromDetail() throws Exception {
    String token = activeToken();
    long external = experience("EXTERNAL", "OPEN");
    var response = detail(token, external);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(code(response)).isEqualTo("EXPERIENCE404_1");
  }

  @Test
  void detailRejectsPendingOnboarding() throws Exception {
    long experienceId = experience("OPEN");
    var signedIn = result(login());
    long userId = signedIn.path("userId").asLong();
    createdUsers.add(userId);
    var response = detail(signedIn.path("accessToken").asText(), experienceId);
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(code(response)).isEqualTo("AUTH403_2");
  }

  @Test
  void detailRequiresAuthentication() throws Exception {
    long experienceId = experience("OPEN");
    assertThat(detail(null, experienceId).statusCode()).isEqualTo(401);
  }

  // --- API-37: 외부 예약 정보 연동·기록 -------------------------------------

  @Test
  void createsReservationWithExternalReferenceAndReturnsTheExactContract() throws Exception {
    String token = activeToken();
    long userId =
        jdbc.queryForObject(
            "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);
    long hobbyId = hobby();
    long providerId = provider("experience-list-test-" + UUID.randomUUID());
    long experienceId =
        detailExperience(
            hobbyId,
            null,
            providerId,
            25000,
            null,
            null,
            null,
            "[]",
            "https://partner.example.com/classes/601",
            null,
            null);
    long sessionId =
        jdbc.queryForObject(
            "INSERT INTO experience_sessions(experience_id,starts_at,duration_minutes,capacity)"
                + " VALUES (?,?,?,?) RETURNING session_id",
            Long.class,
            experienceId,
            java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)),
            60,
            2);
    long userHobbyId = userHobby(userId, hobbyId);

    var response =
        reserve(
            token,
            UUID.randomUUID().toString(),
            experienceId,
            reservationBody(sessionId, userHobbyId, "PARTNER-20261107-1234"));
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(code(response)).isEqualTo("RESERVATION201_1");
    var result = result(response);

    assertThat(fieldNames(result))
        .containsExactlyInAnyOrder(
            "reservationId",
            "experienceId",
            "sessionId",
            "userHobbyId",
            "status",
            "verificationStatus",
            "externalReservationId",
            "bookingUrl",
            "createdAt");
    assertThat(result.path("experienceId").asLong()).isEqualTo(experienceId);
    assertThat(result.path("sessionId").asLong()).isEqualTo(sessionId);
    assertThat(result.path("userHobbyId").asLong()).isEqualTo(userHobbyId);
    assertThat(result.path("status").asText()).isEqualTo("PENDING_VERIFICATION");
    assertThat(result.path("verificationStatus").asText()).isEqualTo("PENDING");
    assertThat(result.path("externalReservationId").asText()).isEqualTo("PARTNER-20261107-1234");
    assertThat(result.path("bookingUrl").asText())
        .isEqualTo("https://partner.example.com/classes/601");

    long reservationId = result.path("reservationId").asLong();
    assertThat(
            jdbc.queryForObject(
                "SELECT session_id FROM experience_reservations WHERE reservation_id=?",
                Long.class,
                reservationId))
        .isEqualTo(sessionId);
    assertThat(
            jdbc.queryForObject(
                "SELECT provider_id FROM experience_reservations WHERE reservation_id=?",
                Long.class,
                reservationId))
        .isEqualTo(providerId);
  }

  @Test
  void blocksDuplicateExternalReferenceWithinANoProviderExperienceButNotAcrossExperiences()
      throws Exception {
    String token = activeToken();
    long experienceId = experience("OPEN");
    long otherExperienceId = experience("OPEN");
    session(experienceId, Instant.now().plus(1, ChronoUnit.DAYS), 30, 5);
    session(otherExperienceId, Instant.now().plus(1, ChronoUnit.DAYS), 30, 5);
    long sessionId =
        jdbc.queryForObject(
            "SELECT session_id FROM experience_sessions WHERE experience_id=?",
            Long.class,
            experienceId);
    long otherSessionId =
        jdbc.queryForObject(
            "SELECT session_id FROM experience_sessions WHERE experience_id=?",
            Long.class,
            otherExperienceId);
    // Neither experience has a provider - provider_id alone can't scope this reference's
    // uniqueness, so it falls back to the experience.
    String ref = "SHARED-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO experience_reservations(reservation_code,experience_id,session_id,user_id,"
            + "provider_id,reservation_status,external_booking_ref) VALUES"
            + " (?,?,?,?,?,'PENDING_VERIFICATION',?)",
        "RSV-" + UUID.randomUUID(),
        experienceId,
        sessionId,
        otherUser(),
        null,
        ref);

    var sameExperience =
        reserve(
            token,
            UUID.randomUUID().toString(),
            experienceId,
            reservationBody(sessionId, null, ref));
    assertThat(sameExperience.statusCode()).isEqualTo(409);
    assertThat(code(sameExperience)).isEqualTo("RESERVATION409_1");

    var differentExperience =
        reserve(
            token,
            UUID.randomUUID().toString(),
            otherExperienceId,
            reservationBody(otherSessionId, null, ref));
    assertThat(differentExperience.statusCode()).isEqualTo(201);
  }

  @Test
  void createsReservationWithoutExternalReferenceAsRedirected() throws Exception {
    String token = activeToken();
    long experienceId =
        detailExperience(null, null, null, null, null, null, null, "[]", null, null, null);
    long sessionId =
        jdbc.queryForObject(
            "INSERT INTO experience_sessions(experience_id,starts_at,duration_minutes) VALUES"
                + " (?,?,60) RETURNING session_id",
            Long.class,
            experienceId,
            java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)));

    var response =
        reserve(
            token,
            UUID.randomUUID().toString(),
            experienceId,
            reservationBody(sessionId, null, null));
    assertThat(response.statusCode()).isEqualTo(201);
    var result = result(response);
    assertThat(result.path("status").asText()).isEqualTo("REDIRECTED");
    assertThat(result.path("verificationStatus").asText()).isEqualTo("NONE");
    assertThat(result.path("userHobbyId").isNull()).isTrue();
    assertThat(result.path("externalReservationId").isNull()).isTrue();
  }

  @Test
  void rejectsWhenSessionDoesNotBelongToTheExperience() throws Exception {
    String token = activeToken();
    long experienceA =
        detailExperience(null, null, null, null, null, null, null, "[]", null, null, null);
    long experienceB =
        detailExperience(null, null, null, null, null, null, null, "[]", null, null, null);
    long sessionOnA =
        jdbc.queryForObject(
            "INSERT INTO experience_sessions(experience_id,starts_at,duration_minutes) VALUES"
                + " (?,?,60) RETURNING session_id",
            Long.class,
            experienceA,
            java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)));

    var response =
        reserve(
            token,
            UUID.randomUUID().toString(),
            experienceB,
            reservationBody(sessionOnA, null, null));
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(code(response)).isEqualTo("EXPERIENCE404_1");
  }

  @Test
  void rejectsWhenSessionOrExperienceIsMissing() throws Exception {
    String token = activeToken();
    long experienceId =
        detailExperience(null, null, null, null, null, null, null, "[]", null, null, null);

    var missingSession =
        reserve(
            token,
            UUID.randomUUID().toString(),
            experienceId,
            reservationBody(9223372036854775807L, null, null));
    assertThat(missingSession.statusCode()).isEqualTo(404);
    assertThat(code(missingSession)).isEqualTo("EXPERIENCE404_1");

    var missingExperience =
        reserve(
            token,
            UUID.randomUUID().toString(),
            9223372036854775807L,
            reservationBody(1L, null, null));
    assertThat(missingExperience.statusCode()).isEqualTo(404);
    assertThat(code(missingExperience)).isEqualTo("EXPERIENCE404_1");
  }

  @Test
  void rejectsWhenExperienceIsNotOpen() throws Exception {
    String token = activeToken();
    for (String status : List.of("COMING_SOON", "CLOSED")) {
      long experienceId =
          detailExperience(null, null, null, null, null, null, null, "[]", null, null, null);
      jdbc.update(
          "UPDATE experiences SET listing_status=? WHERE experience_id=?", status, experienceId);
      long sessionId =
          jdbc.queryForObject(
              "INSERT INTO experience_sessions(experience_id,starts_at,duration_minutes) VALUES"
                  + " (?,?,60) RETURNING session_id",
              Long.class,
              experienceId,
              java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)));

      var response =
          reserve(
              token,
              UUID.randomUUID().toString(),
              experienceId,
              reservationBody(sessionId, null, null));
      assertThat(response.statusCode()).as(status).isEqualTo(409);
      assertThat(code(response)).as(status).isEqualTo("RESERVATION409_1");
    }
  }

  @Test
  void rejectsWhenTheSessionIsFull() throws Exception {
    String token = activeToken();
    long experienceId =
        detailExperience(null, null, null, null, null, null, null, "[]", null, null, null);
    long sessionId =
        jdbc.queryForObject(
            "INSERT INTO experience_sessions(experience_id,starts_at,duration_minutes,capacity)"
                + " VALUES (?,?,60,1) RETURNING session_id",
            Long.class,
            experienceId,
            java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)));
    seedReservation(sessionId, experienceId, otherUser(), null);

    var response =
        reserve(
            token,
            UUID.randomUUID().toString(),
            experienceId,
            reservationBody(sessionId, null, null));
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(code(response)).isEqualTo("RESERVATION409_1");
  }

  @Test
  void rejectsMismatchedHobbyOnUserHobbyId() throws Exception {
    String token = activeToken();
    long userId =
        jdbc.queryForObject(
            "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);
    long experienceHobbyId = hobby();
    long otherHobbyId = hobby();
    long experienceId =
        detailExperience(
            experienceHobbyId, null, null, null, null, null, null, "[]", null, null, null);
    long sessionId =
        jdbc.queryForObject(
            "INSERT INTO experience_sessions(experience_id,starts_at,duration_minutes) VALUES"
                + " (?,?,60) RETURNING session_id",
            Long.class,
            experienceId,
            java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)));
    long userHobbyId = userHobby(userId, otherHobbyId);

    var response =
        reserve(
            token,
            UUID.randomUUID().toString(),
            experienceId,
            reservationBody(sessionId, userHobbyId, null));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(code(response)).isEqualTo("RESERVATION400_1");
  }

  @Test
  void rejectsUnownedUserHobbyId() throws Exception {
    String token = activeToken();
    long otherUserId = otherUser();
    long hobbyId = hobby();
    long experienceId =
        detailExperience(hobbyId, null, null, null, null, null, null, "[]", null, null, null);
    long sessionId =
        jdbc.queryForObject(
            "INSERT INTO experience_sessions(experience_id,starts_at,duration_minutes) VALUES"
                + " (?,?,60) RETURNING session_id",
            Long.class,
            experienceId,
            java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)));
    long notMyUserHobbyId = userHobby(otherUserId, hobbyId);

    var response =
        reserve(
            token,
            UUID.randomUUID().toString(),
            experienceId,
            reservationBody(sessionId, notMyUserHobbyId, null));
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(code(response)).isEqualTo("TREE404_1");
  }

  @Test
  void replaysTheSameResultForARetryWithTheSameIdempotencyKeyAndBody() throws Exception {
    String token = activeToken();
    long experienceId =
        detailExperience(null, null, null, null, null, null, null, "[]", null, null, null);
    long sessionId =
        jdbc.queryForObject(
            "INSERT INTO experience_sessions(experience_id,starts_at,duration_minutes) VALUES"
                + " (?,?,60) RETURNING session_id",
            Long.class,
            experienceId,
            java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)));
    String key = UUID.randomUUID().toString();
    String body = reservationBody(sessionId, null, null);

    var first = result(reserve(token, key, experienceId, body));
    var second = result(reserve(token, key, experienceId, body));
    assertThat(second.path("reservationId").asLong())
        .isEqualTo(first.path("reservationId").asLong());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM experience_reservations WHERE session_id=?",
                Integer.class,
                sessionId))
        .isEqualTo(1);
  }

  @Test
  void reservationRejectsPendingOnboarding() throws Exception {
    long experienceId = experience("OPEN");
    var signedIn = result(login());
    long userId = signedIn.path("userId").asLong();
    createdUsers.add(userId);
    var response =
        reserve(
            signedIn.path("accessToken").asText(),
            UUID.randomUUID().toString(),
            experienceId,
            reservationBody(1L, null, null));
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(code(response)).isEqualTo("AUTH403_2");
  }

  @Test
  void reservationRequiresAuthentication() throws Exception {
    long experienceId = experience("OPEN");
    assertThat(
            reserve(
                    null,
                    UUID.randomUUID().toString(),
                    experienceId,
                    reservationBody(1L, null, null))
                .statusCode())
        .isEqualTo(401);
  }

  @Test
  void reservationRequiresTheIdempotencyKeyHeader() throws Exception {
    String token = activeToken();
    long experienceId = experience("OPEN");
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:" + port + PATH + "/" + experienceId + "/reservations"))
            .timeout(java.time.Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(reservationBody(1L, null, null)));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(code(response)).isEqualTo("COMMON400_1");
  }

  @Test
  void serializesDifferentMembersCompetingForTheLastSessionSeat() throws Exception {
    String firstToken = activeToken();
    var other =
        result(login("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}"));
    long otherId = other.path("userId").asLong();
    createdUsers.add(otherId);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", otherId);
    String secondToken = other.path("accessToken").asText();
    long experienceId = experience("OPEN");
    long sessionId =
        jdbc.queryForObject(
            "INSERT INTO experience_sessions(experience_id,starts_at,duration_minutes,capacity)"
                + " VALUES (?,now()+interval '1 day',60,1) RETURNING session_id",
            Long.class,
            experienceId);
    try (var executor = Executors.newFixedThreadPool(2);
        var blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      try {
        int holderPid;
        try (var statement = blocker.createStatement();
            var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
          rows.next();
          holderPid = rows.getInt(1);
        }
        try (var statement =
            blocker.prepareStatement(
                "SELECT session_id FROM experience_sessions WHERE session_id=? FOR UPDATE")) {
          statement.setLong(1, sessionId);
          try (var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
          }
        }
        var first =
            executor.submit(
                () ->
                    reserve(
                        firstToken,
                        UUID.randomUUID().toString(),
                        experienceId,
                        reservationBody(sessionId, null, null)));
        int firstPid = awaitReservationSessionLock(holderPid, holderPid, first);
        var second =
            executor.submit(
                () ->
                    reserve(
                        secondToken,
                        UUID.randomUUID().toString(),
                        experienceId,
                        reservationBody(sessionId, null, null)));
        awaitReservationSessionLock(holderPid, firstPid, second);
        blocker.commit();
        assertThat(first.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        assertThat(second.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM experience_reservations WHERE session_id=? AND reservation_status<>'CANCELLED'",
                    Integer.class,
                    sessionId))
            .isEqualTo(1);
      } finally {
        blocker.rollback();
      }
    }
  }

  int awaitReservationSessionLock(
      int holderPid, int previousPid, java.util.concurrent.Future<?> request) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      assertThat(request.isDone())
          .as("request waits for the session lock before checking capacity")
          .isFalse();
      var waiting =
          jdbc.queryForList(
              "SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND pid<>?"
                  + " AND query LIKE 'select%experience_sessions%'"
                  + " AND (?=ANY(pg_blocking_pids(pid)) OR ?=ANY(pg_blocking_pids(pid)))",
              Integer.class, previousPid, holderPid, previousPid);
      if (!waiting.isEmpty()) return waiting.getFirst();
      Thread.sleep(20);
    }
    throw new AssertionError("Request did not wait for the session read lock");
  }

  @Test
  void unresolvedLegacyReservationsPreventNewBookings() throws Exception {
    String token = activeToken();
    long experienceId = experience("OPEN");
    long legacyOwner = otherUser();
    jdbc.update(
        "INSERT INTO experience_reservations(reservation_code,experience_id,user_id,reservation_status) VALUES (?,?,?,'REQUESTED')",
        "legacy-" + UUID.randomUUID(),
        experienceId,
        legacyOwner);
    long sessionId =
        jdbc.queryForObject(
            "INSERT INTO experience_sessions(experience_id,starts_at,duration_minutes,capacity) VALUES (?,now()+interval '1 day',60,1) RETURNING session_id",
            Long.class,
            experienceId);
    assertThat(
            reserve(
                    token,
                    UUID.randomUUID().toString(),
                    experienceId,
                    reservationBody(sessionId, null, null))
                .statusCode())
        .isEqualTo(409);
  }

  // --- API-38: 내 예약 목록 조회 -----------------------------------------

  @Test
  void returnsMyReservationsWithTheExactContract() throws Exception {
    String token = activeToken();
    long userId =
        jdbc.queryForObject(
            "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);
    long hobbyId = hobby();
    long providerId = provider("experience-list-test-" + UUID.randomUUID());
    long experienceId =
        detailExperience(
            hobbyId,
            null,
            providerId,
            25000,
            null,
            null,
            null,
            "[]",
            "https://partner.example.com/classes/601",
            "https://partner.example.com/policies/cancellation",
            null);
    Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    session(experienceId, startsAt, 60, 5);
    long sessionId =
        jdbc.queryForObject(
            "SELECT session_id FROM experience_sessions WHERE experience_id=?",
            Long.class,
            experienceId);
    long userHobbyId = userHobby(userId, hobbyId);
    String title =
        jdbc.queryForObject(
            "SELECT title FROM experiences WHERE experience_id=?", String.class, experienceId);

    var createResponse =
        reserve(
            token,
            UUID.randomUUID().toString(),
            experienceId,
            reservationBody(sessionId, userHobbyId, "PARTNER-20261107-9999"));
    assertThat(createResponse.statusCode()).isEqualTo(201);
    long reservationId = result(createResponse).path("reservationId").asLong();

    var response = myReservations(token, "");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(code(response)).isEqualTo("RESERVATION200_1");
    var result = result(response);
    assertThat(fieldNames(result)).containsExactlyInAnyOrder("items", "nextCursor", "hasNext");
    assertThat(result.path("items")).hasSize(1);
    var item = result.path("items").get(0);
    assertThat(fieldNames(item))
        .containsExactlyInAnyOrder(
            "reservationId",
            "experienceId",
            "title",
            "sessionId",
            "startsAt",
            "userHobbyId",
            "status",
            "verificationStatus",
            "attended",
            "bookingUrl",
            "cancellationPolicyUrl",
            "createdAt");
    assertThat(item.path("reservationId").asLong()).isEqualTo(reservationId);
    assertThat(item.path("experienceId").asLong()).isEqualTo(experienceId);
    assertThat(item.path("title").asText()).isEqualTo(title);
    assertThat(item.path("sessionId").asLong()).isEqualTo(sessionId);
    assertThat(item.path("startsAt").asText()).isEqualTo(startsAt.toString());
    assertThat(item.path("userHobbyId").asLong()).isEqualTo(userHobbyId);
    assertThat(item.path("status").asText()).isEqualTo("PENDING_VERIFICATION");
    assertThat(item.path("verificationStatus").asText()).isEqualTo("PENDING");
    assertThat(item.path("attended").asBoolean()).isFalse();
    assertThat(item.path("bookingUrl").asText())
        .isEqualTo("https://partner.example.com/classes/601");
    assertThat(item.path("cancellationPolicyUrl").asText())
        .isEqualTo("https://partner.example.com/policies/cancellation");
    assertThat(result.path("nextCursor").isNull()).isTrue();
    assertThat(result.path("hasNext").asBoolean()).isFalse();
  }

  @Test
  void onlyReturnsTheCallersOwnReservations() throws Exception {
    String token = activeToken();
    long experienceId = experience("OPEN");
    session(experienceId, Instant.now().plus(1, ChronoUnit.DAYS), 30, 5);
    long sessionId =
        jdbc.queryForObject(
            "SELECT session_id FROM experience_sessions WHERE experience_id=?",
            Long.class,
            experienceId);
    seedReservation(sessionId, experienceId, otherUser(), null, "REDIRECTED");

    var result = result(myReservations(token, ""));
    assertThat(result.path("items")).isEmpty();
  }

  @Test
  void excludesLegacyReservationsWithNoResolvedSession() throws Exception {
    String token = activeToken();
    long userId =
        jdbc.queryForObject(
            "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);
    long experienceId = experience("OPEN");
    jdbc.update(
        "INSERT INTO experience_reservations(reservation_code,experience_id,user_id,"
            + "reservation_status) VALUES (?,?,?,'REQUESTED')",
        "legacy-" + UUID.randomUUID(),
        experienceId,
        userId);

    var result = result(myReservations(token, ""));
    assertThat(result.path("items")).isEmpty();
  }

  @Test
  void returnsAnEmptyListWhenThereAreNoReservations() throws Exception {
    String token = activeToken();
    var result = result(myReservations(token, ""));
    assertThat(result.path("items")).isEmpty();
    assertThat(result.path("nextCursor").isNull()).isTrue();
    assertThat(result.path("hasNext").asBoolean()).isFalse();
  }

  @Test
  void filtersByStatusAndTranslatesCompletedToTheStoredAttendedValue() throws Exception {
    String token = activeToken();
    long userId =
        jdbc.queryForObject(
            "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);
    long experienceId = experience("OPEN");
    // One session per row: (session_id, user_id) is unique, and each status needs its own
    // row for the same caller.
    for (int i = 0; i < 4; i++) {
      session(experienceId, Instant.now().plus(i + 1, ChronoUnit.DAYS), 30, 5);
    }
    List<Long> sessionIds =
        jdbc.queryForList(
            "SELECT session_id FROM experience_sessions WHERE experience_id=? ORDER BY session_id",
            Long.class,
            experienceId);
    seedMyReservation(sessionIds.get(0), experienceId, userId, "REDIRECTED", null, null);
    seedMyReservation(sessionIds.get(1), experienceId, userId, "CONFIRMED", "PARTNER-A", null);
    seedMyReservation(sessionIds.get(2), experienceId, userId, "CANCELLED", null, null);
    seedMyReservation(
        sessionIds.get(3), experienceId, userId, "ATTENDED", "PARTNER-B", Instant.now());

    var confirmed = result(myReservations(token, "?status=CONFIRMED"));
    assertThat(confirmed.path("items")).hasSize(1);
    assertThat(confirmed.path("items").get(0).path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(confirmed.path("items").get(0).path("verificationStatus").asText())
        .isEqualTo("VERIFIED");

    var cancelled = result(myReservations(token, "?status=CANCELLED"));
    assertThat(cancelled.path("items")).hasSize(1);
    assertThat(cancelled.path("items").get(0).path("verificationStatus").asText())
        .isEqualTo("NONE");

    var completed = result(myReservations(token, "?status=COMPLETED"));
    assertThat(completed.path("items")).hasSize(1);
    assertThat(completed.path("items").get(0).path("status").asText()).isEqualTo("COMPLETED");
    assertThat(completed.path("items").get(0).path("verificationStatus").asText())
        .isEqualTo("VERIFIED");
    assertThat(completed.path("items").get(0).path("attended").asBoolean()).isTrue();

    var all = result(myReservations(token, ""));
    assertThat(all.path("items")).hasSize(4);
  }

  @Test
  void rejectsAnUnsupportedStatusFilter() throws Exception {
    String token = activeToken();
    assertThat(code(myReservations(token, "?status=REQUESTED"))).isEqualTo("COMMON400_1");
    assertThat(code(myReservations(token, "?status=NOT_A_STATUS"))).isEqualTo("COMMON400_1");
  }

  @Test
  void rejectsAnOutOfRangeSize() throws Exception {
    String token = activeToken();
    assertThat(code(myReservations(token, "?size=0"))).isEqualTo("COMMON400_1");
    assertThat(code(myReservations(token, "?size=51"))).isEqualTo("COMMON400_1");
  }

  @Test
  void rejectsAnInvalidCursor() throws Exception {
    String token = activeToken();
    assertThat(code(myReservations(token, "?cursor=not-a-valid-cursor"))).isEqualTo("COMMON400_1");
  }

  @Test
  void requiresAuthentication() throws Exception {
    assertThat(myReservations(null, "").statusCode()).isEqualTo(401);
  }

  @Test
  void rejectsPendingOnboarding() throws Exception {
    var signedIn = result(login());
    long userId = signedIn.path("userId").asLong();
    createdUsers.add(userId);
    String token = signedIn.path("accessToken").asText();
    assertThat(code(myReservations(token, ""))).isEqualTo("AUTH403_2");
  }

  @Test
  void paginatesMyReservationsWithACursor() throws Exception {
    String token = activeToken();
    long userId =
        jdbc.queryForObject(
            "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);
    long experienceId = experience("OPEN");
    for (int i = 0; i < 3; i++) {
      session(experienceId, Instant.now().plus(i + 1, ChronoUnit.DAYS), 30, null);
    }
    List<Long> sessionIds =
        jdbc.queryForList(
            "SELECT session_id FROM experience_sessions WHERE experience_id=? ORDER BY session_id",
            Long.class,
            experienceId);
    for (long sessionId : sessionIds) {
      seedMyReservation(sessionId, experienceId, userId, "REDIRECTED", null, null);
      // bookedAt has DB-clock second-level granularity risk of ties across fast inserts -
      // reservation_id is the documented tie-breaker, so this only needs distinct rows, not
      // distinct timestamps.
    }

    var firstPage = result(myReservations(token, "?size=2"));
    assertThat(firstPage.path("items")).hasSize(2);
    assertThat(firstPage.path("hasNext").asBoolean()).isTrue();
    String cursor = firstPage.path("nextCursor").asText();

    var secondPage =
        result(
            myReservations(token, "?size=2&cursor=" + java.net.URLEncoder.encode(cursor, "UTF-8")));
    assertThat(secondPage.path("items")).hasSize(1);
    assertThat(secondPage.path("hasNext").asBoolean()).isFalse();

    var firstIds =
        toList(firstPage.path("items")).stream()
            .map(n -> n.path("reservationId").asLong())
            .toList();
    var secondIds =
        toList(secondPage.path("items")).stream()
            .map(n -> n.path("reservationId").asLong())
            .toList();
    assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
    assertThat(firstIds.get(0)).isGreaterThan(firstIds.get(1));
    assertThat(firstIds.get(1)).isGreaterThan(secondIds.get(0));
  }

  // Seeds a reservation with full control over status/external ref/attendance - states
  // (CONFIRMED, CANCELLED, ATTENDED) that no real flow in this codebase can reach yet, needed
  // to test API-38's status filter and its derived verificationStatus/attended fields.
  void seedMyReservation(
      long sessionId,
      long experienceId,
      long userId,
      String status,
      String externalBookingRef,
      Instant attendanceVerifiedAt) {
    jdbc.update(
        "INSERT INTO experience_reservations(reservation_code,experience_id,session_id,user_id,"
            + "reservation_status,external_booking_ref,attendance_verified_at) VALUES"
            + " (?,?,?,?,?,?,?)",
        "RSV-" + UUID.randomUUID(),
        experienceId,
        sessionId,
        userId,
        status,
        externalBookingRef,
        attendanceVerifiedAt == null ? null : java.sql.Timestamp.from(attendanceVerifiedAt));
  }

  private List<JsonNode> toList(JsonNode array) {
    List<JsonNode> result = new java.util.ArrayList<>();
    array.forEach(result::add);
    return result;
  }
}
