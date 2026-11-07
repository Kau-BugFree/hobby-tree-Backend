package com.hobbytree.hobbytree.domain.preference;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class PreferenceIntegrationTests extends IntegrationTestSupport {
  // Must track OnboardingService.OPTIONS_VERSION exactly.
  private static final String CURRENT_OPTIONS_VERSION = "2026-11-07";

  String activeToken() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return signedIn.path("accessToken").asText();
  }

  HttpResponse<String> putPreferences(String token, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/users/me/preferences"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  String body(
      String budgetLevel,
      int minutes,
      String indoorOutdoor,
      String difficulty,
      List<Integer> categoryIds,
      int expectedVersion) {
    return body(
        CURRENT_OPTIONS_VERSION,
        budgetLevel,
        minutes,
        indoorOutdoor,
        difficulty,
        categoryIds,
        expectedVersion);
  }

  String body(
      String optionsVersion,
      String budgetLevel,
      int minutes,
      String indoorOutdoor,
      String difficulty,
      List<Integer> categoryIds,
      int expectedVersion) {
    return """
        {"optionsVersion":"%s","budgetLevel":"%s","availableTimeMinutes":%d,
         "indoorOutdoor":"%s","difficultyPreference":"%s","preferredCategoryIds":%s,
         "expectedVersion":%d}
        """
        .formatted(
            optionsVersion,
            budgetLevel,
            minutes,
            indoorOutdoor,
            difficulty,
            categoryIds,
            expectedVersion);
  }

  List<String> fieldNames(JsonNode node) {
    return new ArrayList<>(node.propertyNames());
  }

  @Test
  void savesPreferencesAndReturnsTheExactContract() throws Exception {
    String token = activeToken();
    long id =
        jdbc.queryForObject(
            "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);

    var response = putPreferences(token, body("LOW", 30, "INDOOR", "BEGINNER", List.of(1, 2), 0));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var envelope = mapper.readTree(response.body());
    assertThat(envelope.path("code").asText()).isEqualTo("PREFERENCE200_1");
    assertThat(envelope.path("message").asText()).isEqualTo("취향 설정이 저장되었습니다.");
    var result = envelope.path("result");
    assertThat(fieldNames(result))
        .containsExactlyInAnyOrder(
            "optionsVersion",
            "budgetLevel",
            "availableTimeMinutes",
            "indoorOutdoor",
            "difficultyPreference",
            "preferredCategoryIds",
            "userId",
            "preferencesVersion",
            "updatedAt");
    assertThat(result.path("optionsVersion").asText()).isEqualTo(CURRENT_OPTIONS_VERSION);
    assertThat(result.path("budgetLevel").asText()).isEqualTo("LOW");
    assertThat(result.path("availableTimeMinutes").asInt()).isEqualTo(30);
    assertThat(result.path("indoorOutdoor").asText()).isEqualTo("INDOOR");
    assertThat(result.path("difficultyPreference").asText()).isEqualTo("BEGINNER");
    var ids = new ArrayList<Integer>();
    result.path("preferredCategoryIds").forEach(n -> ids.add(n.asInt()));
    assertThat(ids).containsExactly(1, 2);
    assertThat(result.path("userId").asLong()).isEqualTo(id);
    assertThat(result.path("preferencesVersion").asInt()).isEqualTo(1);
    assertThat(result.path("updatedAt").asText()).isNotBlank();
  }

  @Test
  void allowsAnEmptyPreferredCategoryIdsList() throws Exception {
    String token = activeToken();
    var response = putPreferences(token, body("HIGH", 120, "ANY", "ANY", List.of(), 0));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(result(response).path("preferredCategoryIds")).isEmpty();
  }

  @Test
  void resendingIdenticalContentKeepsTheSamePreferencesVersion() throws Exception {
    String token = activeToken();
    putPreferences(token, body("LOW", 30, "INDOOR", "BEGINNER", List.of(1, 2), 0));

    var second = putPreferences(token, body("LOW", 30, "INDOOR", "BEGINNER", List.of(2, 1), 1));
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(result(second).path("preferencesVersion").asInt()).isEqualTo(1);

    var third = putPreferences(token, body("LOW", 30, "INDOOR", "BEGINNER", List.of(1, 3), 1));
    assertThat(third.statusCode()).isEqualTo(200);
    assertThat(result(third).path("preferencesVersion").asInt()).isEqualTo(2);
  }

  @Test
  void rejectsWhenOptionsVersionHasChanged() throws Exception {
    String token = activeToken();
    var response =
        putPreferences(token, body("2020-01-01", "LOW", 30, "INDOOR", "BEGINNER", List.of(), 0));
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("PREFERENCE409_1");
  }

  @Test
  void rejectsWhenExpectedVersionMismatches() throws Exception {
    String token = activeToken();
    var response = putPreferences(token, body("LOW", 30, "INDOOR", "BEGINNER", List.of(), 5));
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("PREFERENCE409_2");

    putPreferences(token, body("LOW", 30, "INDOOR", "BEGINNER", List.of(), 0));
    var stale = putPreferences(token, body("MEDIUM", 60, "OUTDOOR", "ANY", List.of(), 0));
    assertThat(stale.statusCode()).isEqualTo(409);
    assertThat(mapper.readTree(stale.body()).path("code").asText()).isEqualTo("PREFERENCE409_2");
  }

  @Test
  void rejectsInvalidSelections() throws Exception {
    String token = activeToken();
    for (String invalidBody :
        List.of(
            body("WRONG", 30, "INDOOR", "BEGINNER", List.of(), 0),
            body("LOW", 45, "INDOOR", "BEGINNER", List.of(), 0),
            body("LOW", 30, "SIDEWAYS", "BEGINNER", List.of(), 0),
            body("LOW", 30, "INDOOR", "EXPERT", List.of(), 0),
            body("LOW", 30, "INDOOR", "BEGINNER", List.of(1, 2, 3, 1), 0),
            body("LOW", 30, "INDOOR", "BEGINNER", List.of(1, 1), 0),
            body("LOW", 30, "INDOOR", "BEGINNER", List.of(99), 0))) {
      var response = putPreferences(token, invalidBody);
      assertThat(response.statusCode()).as(invalidBody).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(invalidBody)
          .isEqualTo("PREFERENCE400_1");
    }
  }

  @Test
  void rejectsMissingRequiredFields() throws Exception {
    String token = activeToken();
    for (String invalidBody :
        List.of(
            "{\"budgetLevel\":\"LOW\",\"availableTimeMinutes\":30,\"indoorOutdoor\":\"INDOOR\","
                + "\"difficultyPreference\":\"BEGINNER\",\"preferredCategoryIds\":[],\"expectedVersion\":0}",
            "{\"optionsVersion\":\"%s\",\"availableTimeMinutes\":30,\"indoorOutdoor\":\"INDOOR\","
                    .formatted(CURRENT_OPTIONS_VERSION)
                + "\"difficultyPreference\":\"BEGINNER\",\"preferredCategoryIds\":[],\"expectedVersion\":0}",
            "{}")) {
      var response = putPreferences(token, invalidBody);
      assertThat(response.statusCode()).as(invalidBody).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(invalidBody)
          .isEqualTo("COMMON400_1");
    }
  }

  @Test
  void allowsCallDuringPendingOnboarding() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String token = signedIn.path("accessToken").asText();

    var response = putPreferences(token, body("LOW", 30, "INDOOR", "BEGINNER", List.of(), 0));
    assertThat(response.statusCode()).isEqualTo(200);
  }

  @Test
  void doesNotAffectNotificationFrequencyOrOtherUsers() throws Exception {
    String token = activeToken();
    long id =
        jdbc.queryForObject(
            "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);
    jdbc.update(
        "INSERT INTO user_preferences (user_id, notification_frequency) VALUES (?, 'WEEKLY')", id);

    var other =
        result(login("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}"));
    long otherId = other.path("userId").asLong();
    createdUsers.add(otherId);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", otherId);

    putPreferences(token, body("LOW", 30, "INDOOR", "BEGINNER", List.of(), 0));
    assertThat(
            jdbc.queryForObject(
                "SELECT notification_frequency FROM user_preferences WHERE user_id=?",
                String.class,
                id))
        .isEqualTo("WEEKLY");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM user_preferences WHERE user_id=?", Integer.class, otherId))
        .isZero();
  }

  @Test
  void requiresAuthenticationAndRejectsSuspendedOrWithdrawnMembers() throws Exception {
    assertThat(
            putPreferences(null, body("LOW", 30, "INDOOR", "BEGINNER", List.of(), 0)).statusCode())
        .isEqualTo(401);

    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String token = signedIn.path("accessToken").asText();

    for (String status : List.of("SUSPENDED", "WITHDRAWN")) {
      jdbc.update(
          "UPDATE users SET account_status=?, withdrawn_at=CASE WHEN ?='WITHDRAWN' THEN now()"
              + " ELSE NULL END, restorable_until=CASE WHEN ?='WITHDRAWN' THEN now()+interval"
              + " '30 days' ELSE NULL END, status_before_withdrawal=CASE WHEN ?='WITHDRAWN'"
              + " THEN 'ACTIVE' ELSE NULL END WHERE user_id=?",
          status,
          status,
          status,
          status,
          id);
      assertThat(
              putPreferences(token, body("LOW", 30, "INDOOR", "BEGINNER", List.of(), 0))
                  .statusCode())
          .as(status)
          .isEqualTo(403);
    }
  }
}
