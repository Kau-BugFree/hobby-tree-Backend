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
    verify(naverClient).search("강남구 요리");

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
