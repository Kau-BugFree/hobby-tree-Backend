package com.hobbytree.hobbytree.domain.localbusiness;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.localbusiness.client.NaverLocalSearchClient;
import com.hobbytree.hobbytree.domain.localbusiness.client.NaverLocalSearchClient.NaverLocalItem;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

// Issue #191: end-to-end coverage of search -> select (find-or-create) -> the unchanged
// API-37 reservation flow, with the one piece that talks to Naver (NaverLocalSearchClient)
// mocked - same pattern as KakaoCoordinateRegionResolver in UserLocationIntegrationTests.
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class LocalBusinessIntegrationTests extends IntegrationTestSupport {
  @MockitoBean private NaverLocalSearchClient naverClient;
  private final List<Long> createdExperienceIds = new java.util.ArrayList<>();
  private final List<Long> createdHobbyIds = new java.util.ArrayList<>();

  @AfterEach
  void cleanupExperiences() {
    for (Long id : createdExperienceIds) {
      jdbc.update("DELETE FROM experience_reservations WHERE experience_id=?", id);
      jdbc.update("DELETE FROM experience_sessions WHERE experience_id=?", id);
      jdbc.update("DELETE FROM experiences WHERE experience_id=?", id);
    }
    createdExperienceIds.clear();
    for (Long id : createdHobbyIds) jdbc.update("DELETE FROM hobbies WHERE hobby_id=?", id);
    createdHobbyIds.clear();
  }

  // This scratch test database has no seeded hobby catalog, unlike the real dev/prod DB -
  // other *IntegrationTests classes (e.g. ExperienceIntegrationTests.hobby()) seed their own
  // rows the same way.
  long seedHobby(String category) {
    long id =
        jdbc.queryForObject(
            "INSERT INTO hobbies(hobby_code,hobby_name,category) VALUES (?,?,?) RETURNING hobby_id",
            Long.class,
            "local-business-test-" + UUID.randomUUID().toString().substring(0, 12),
            "로컬비즈니스 테스트",
            category);
    createdHobbyIds.add(id);
    return id;
  }

  HttpResponse<String> search(String token, String region, String hobby) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/v1/local-businesses?region="
                        + region
                        + "&hobby="
                        + hobby))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> select(String token, Object body) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/local-businesses/select"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> reserve(String token, long experienceId, long sessionId) throws Exception {
    return client.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/v1/experiences/"
                        + experienceId
                        + "/reservations"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Authorization", "Bearer " + token)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(Map.of("sessionId", sessionId))))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  NaverLocalItem fakeItem() {
    return new NaverLocalItem(
        "맛있는 요리 교실", "교육>요리학원", "설명", "02-123-4567", "서울 강남구", "서울 강남구 테헤란로", "111", "222");
  }

  @Test
  void searchThenSelectThenReservesThroughTheExistingReservationEndpoint() throws Exception {
    seedHobby("COOKING");
    when(naverClient.search(anyString())).thenReturn(List.of(fakeItem()));

    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String access = signedIn.path("accessToken").asText();
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);

    var searchResponse = search(access, "강남구", "COOKING");
    assertThat(searchResponse.statusCode()).isEqualTo(200);
    var searched = result(searchResponse);
    assertThat(searched.path("region").asText()).isEqualTo("강남구");
    JsonNode item = searched.path("items").get(0);
    assertThat(item.path("name").asText()).isEqualTo("맛있는 요리 교실");
    verify(naverClient).search("강남구 요리교실");

    Map<String, Object> selectBody =
        Map.of(
            "hobby", "COOKING",
            "name", item.path("name").asText(),
            "category", item.path("category").asText(),
            "address", item.path("address").asText(),
            "mapX", item.path("mapX").asText(),
            "mapY", item.path("mapY").asText());
    var firstSelect = select(access, selectBody);
    assertThat(firstSelect.statusCode()).isEqualTo(200);
    var selected = result(firstSelect);
    long experienceId = selected.path("experienceId").asLong();
    long sessionId = selected.path("sessionId").asLong();
    assertThat(selected.path("isNew").asBoolean()).isTrue();
    createdExperienceIds.add(experienceId);

    assertThat(
            jdbc.queryForObject(
                "SELECT external_source FROM experiences WHERE experience_id=?",
                String.class,
                experienceId))
        .isEqualTo("NAVER");
    assertThat(
            jdbc.queryForObject(
                "SELECT listing_status FROM experiences WHERE experience_id=?",
                String.class,
                experienceId))
        .isEqualTo("OPEN");
    assertThat(
            jdbc.queryForObject(
                "SELECT booking_mode FROM experiences WHERE experience_id=?",
                String.class,
                experienceId))
        .isEqualTo("EXTERNAL");

    // Selecting the exact same business again reuses the row instead of creating a duplicate.
    var secondSelect = result(select(access, selectBody));
    assertThat(secondSelect.path("experienceId").asLong()).isEqualTo(experienceId);
    assertThat(secondSelect.path("isNew").asBoolean()).isFalse();
    assertThat(secondSelect.path("sessionId").asLong()).isEqualTo(sessionId);

    // The unchanged API-37 flow accepts this experienceId/sessionId as if it were any other
    // bookable class.
    var reserveResponse = reserve(access, experienceId, sessionId);
    assertThat(reserveResponse.statusCode()).isEqualTo(201);
    var reserved = result(reserveResponse);
    assertThat(reserved.path("experienceId").asLong()).isEqualTo(experienceId);
    assertThat(reserved.path("sessionId").asLong()).isEqualTo(sessionId);
    assertThat(reserved.path("status").asText()).isNotBlank();
  }

  // Issue #191 review: the DataIntegrityViolationException retry must run in a fresh
  // transaction/connection, not the one Postgres just aborted - exercised here against a real
  // database and a real unique-constraint race, not a mock.
  @Test
  void concurrentSelectOfTheSameBusinessOnlyCreatesOneExperience() throws Exception {
    seedHobby("COOKING");
    when(naverClient.search(anyString())).thenReturn(List.of(fakeItem()));

    var signedIn = result(login());
    createdUsers.add(signedIn.path("userId").asLong());
    String access = signedIn.path("accessToken").asText();

    Map<String, Object> selectBody =
        Map.of(
            "hobby", "COOKING",
            "name", "맛있는 요리 교실",
            "category", "교육>요리학원",
            "address", "서울 강남구",
            "mapX", "333",
            "mapY", "444");

    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<HttpResponse<String>> call =
          () -> {
            start.await();
            return select(access, selectBody);
          };
      var a = executor.submit(call);
      var b = executor.submit(call);
      start.countDown();
      var responses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));

      assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
      var ids =
          responses.stream().map(r -> result(r).path("experienceId").asLong()).distinct().toList();
      assertThat(ids).hasSize(1);
      createdExperienceIds.add(ids.get(0));
      assertThat(responses.stream().map(r -> result(r).path("sessionId").asLong()).distinct())
          .hasSize(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM experience_sessions WHERE experience_id=?",
                  Integer.class,
                  ids.get(0)))
          .isEqualTo(1);
      assertThat(responses.stream().filter(r -> result(r).path("isNew").asBoolean()).count())
          .isEqualTo(1);
    }
  }

  @Test
  void concurrentSelectOfAnExistingBusinessWithoutUpcomingSessionsReusesOneSession()
      throws Exception {
    seedHobby("COOKING");
    var signedIn = result(login());
    createdUsers.add(signedIn.path("userId").asLong());
    String access = signedIn.path("accessToken").asText();
    Map<String, Object> body =
        Map.of("hobby", "COOKING", "name", "동시 선택 테스트", "mapX", "555", "mapY", "666");
    long experienceId = result(select(access, body)).path("experienceId").asLong();
    createdExperienceIds.add(experienceId);
    jdbc.update("DELETE FROM experience_sessions WHERE experience_id=?", experienceId);

    try (var holder = dataSource.getConnection();
        var executor = Executors.newFixedThreadPool(2)) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement(
              "SELECT experience_id FROM experiences WHERE experience_id=? FOR UPDATE")) {
        statement.setLong(1, experienceId);
        statement.executeQuery().close();
      }
      var a = executor.submit(() -> select(access, body));
      var b = executor.submit(() -> select(access, body));
      try {
        // Both requests must reach a DB lock wait before release. Without the service's
        // parent lock both read an empty session list and block only on the INSERT's FK
        // check; after release they create two rows. With the fix they recheck in order.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int waiting;
        do {
          waiting =
              jdbc.queryForObject(
                  "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                      + " AND wait_event_type='Lock' AND query LIKE '%experien%'",
                  Integer.class);
          if (waiting >= 2) break;
          Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertThat(waiting).isGreaterThanOrEqualTo(2);
      } finally {
        holder.rollback();
      }
      var responses = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
      assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
      assertThat(responses.stream().map(r -> result(r).path("sessionId").asLong()).distinct())
          .hasSize(1);
      assertThat(responses)
          .allSatisfy(
              r -> {
                assertThat(result(r).path("experienceId").asLong()).isEqualTo(experienceId);
                assertThat(result(r).path("isNew").asBoolean()).isFalse();
              });
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM experience_sessions WHERE experience_id=?",
                  Integer.class,
                  experienceId))
          .isEqualTo(1);
    }
  }

  @Test
  void searchRequiresAuthentication() throws Exception {
    assertThat(search(null, "강남구", "COOKING").statusCode()).isEqualTo(401);
  }

  @Test
  void selectRequiresAuthentication() throws Exception {
    var response = select(null, Map.of("hobby", "COOKING", "name", "x", "mapX", "1", "mapY", "2"));
    assertThat(response.statusCode()).isEqualTo(401);
  }
}
