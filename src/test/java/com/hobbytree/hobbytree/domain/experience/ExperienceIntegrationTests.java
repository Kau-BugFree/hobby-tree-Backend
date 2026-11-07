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

  String code(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("code").asText();
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    // experiences isn't owned by a user (no cascade from deleting the user), so it must be
    // cleaned up explicitly; a title-prefix match is simpler than tracking each seeded id.
    jdbc.update(
        "DELETE FROM experience_sessions WHERE experience_id IN"
            + " (SELECT experience_id FROM experiences WHERE title LIKE 'class-open-test-%'"
            + " OR title LIKE 'experience-list-test-%')");
    jdbc.update(
        "DELETE FROM experiences WHERE title LIKE 'class-open-test-%'"
            + " OR title LIKE 'experience-list-test-%'");
    jdbc.update("DELETE FROM hobbies WHERE hobby_code LIKE 'experience-list-test-%'");
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

  private List<JsonNode> toList(JsonNode array) {
    List<JsonNode> result = new java.util.ArrayList<>();
    array.forEach(result::add);
    return result;
  }
}
