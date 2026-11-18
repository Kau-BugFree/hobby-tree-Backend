package com.hobbytree.hobbytree.domain.location;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.location.service.KakaoCoordinateRegionResolver;
import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

// user_locations/location_consents both cascade from users (ON DELETE CASCADE), so the base
// class's @AfterEach cleanup is enough - nothing to override here.
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class LocationConsentIntegrationTests extends IntegrationTestSupport {
  @MockitoBean private KakaoCoordinateRegionResolver kakaoResolver;

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

  HttpResponse<String> postConsent(String token, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/users/me/location-consents"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> putLocation(String token, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me/location"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> getMyProfile(String token) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me"))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  String consentBody(boolean agreed, String version) {
    return "{\"consentType\":\"NEARBY_EXPERIENCES\",\"version\":\"%s\",\"agreed\":%s}"
        .formatted(version, agreed);
  }

  List<String> fieldNames(JsonNode node) {
    return new ArrayList<>(node.propertyNames());
  }

  @Test
  void recordsAgreedConsentAndReturnsTheExactContract() throws Exception {
    String token = activeToken();

    var response = postConsent(token, consentBody(true, "1.1"));
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var envelope = mapper.readTree(response.body());
    assertThat(envelope.path("code").asText()).isEqualTo("LOCATION201_1");
    assertThat(envelope.path("message").asText()).isEqualTo("위치 이용 동의 상태가 기록되었습니다.");
    var result = envelope.path("result");
    assertThat(fieldNames(result))
        .containsExactlyInAnyOrder(
            "consentId", "consentType", "version", "agreed", "recordedAt", "locationCleared");
    assertThat(result.path("consentId").asLong()).isPositive();
    assertThat(result.path("consentType").asText()).isEqualTo("NEARBY_EXPERIENCES");
    assertThat(result.path("version").asText()).isEqualTo("1.1");
    assertThat(result.path("agreed").asBoolean()).isTrue();
    assertThat(result.path("recordedAt").asText()).isNotBlank();
    assertThat(result.path("locationCleared").asBoolean()).isFalse();

    long rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM location_consents WHERE user_id=?", Long.class, currentUserId());
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void repeatingTheSameDecisionAppendsHistoryButKeepsCurrentStateTheSame() throws Exception {
    String token = activeToken();
    postConsent(token, consentBody(true, "1.1"));
    var second = postConsent(token, consentBody(true, "1.1"));

    assertThat(second.statusCode()).isEqualTo(201);
    assertThat(result(second).path("agreed").asBoolean()).isTrue();
    long rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM location_consents WHERE user_id=?", Long.class, currentUserId());
    assertThat(rows).isEqualTo(2);
  }

  @Test
  void withdrawingConsentClearsOnlyADeviceSourcedLocation() throws Exception {
    String token = activeToken();
    postConsent(token, consentBody(true, "1.1"));
    when(kakaoResolver.resolveSigunguRegionId(anyDouble(), anyDouble())).thenReturn(11680L);
    putLocation(
        token,
        "{\"source\":\"DEVICE\",\"latitude\":37.57,\"longitude\":126.97,\"consentVersion\":\"1.1\"}");

    var withdraw = postConsent(token, consentBody(false, "1.1"));
    assertThat(withdraw.statusCode()).isEqualTo(201);
    assertThat(result(withdraw).path("locationCleared").asBoolean()).isTrue();

    var profile = result(getMyProfile(token));
    assertThat(profile.path("location").isNull()).isTrue();
  }

  @Test
  void withdrawingConsentLeavesAManualLocationUntouched() throws Exception {
    String token = activeToken();
    postConsent(token, consentBody(true, "1.1"));
    putLocation(token, "{\"source\":\"MANUAL\",\"regionId\":11110}");

    var withdraw = postConsent(token, consentBody(false, "1.1"));
    assertThat(withdraw.statusCode()).isEqualTo(201);
    assertThat(result(withdraw).path("locationCleared").asBoolean()).isFalse();

    var profile = result(getMyProfile(token));
    assertThat(profile.path("location").path("regionId").asLong()).isEqualTo(11110L);
  }

  @Test
  void withdrawingConsentWithNoExistingLocationReportsNotCleared() throws Exception {
    String token = activeToken();
    var withdraw = postConsent(token, consentBody(false, "1.1"));
    assertThat(withdraw.statusCode()).isEqualTo(201);
    assertThat(result(withdraw).path("locationCleared").asBoolean()).isFalse();
  }

  @Test
  void recordedConsentThroughThisApiUnblocksTheDeviceLocationPath() throws Exception {
    String token = activeToken();
    postConsent(token, consentBody(true, "1.1"));
    when(kakaoResolver.resolveSigunguRegionId(37.57, 126.97)).thenReturn(11680L);

    var response =
        putLocation(
            token,
            "{\"source\":\"DEVICE\",\"latitude\":37.57,\"longitude\":126.97,\"consentVersion\":\"1.1\"}");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(result(response).path("location").path("source").asText()).isEqualTo("DEVICE");
  }

  @Test
  void rejectsMissingRequiredFields() throws Exception {
    String token = activeToken();
    for (String body :
        List.of(
            "{\"version\":\"1.1\",\"agreed\":true}",
            "{\"consentType\":\"NEARBY_EXPERIENCES\",\"agreed\":true}",
            "{\"consentType\":\"NEARBY_EXPERIENCES\",\"version\":\"1.1\"}")) {
      var response = postConsent(token, body);
      assertThat(response.statusCode()).as(body).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(body)
          .isEqualTo("COMMON400_1");
    }
  }

  @Test
  void rejectsUnsupportedConsentType() throws Exception {
    String token = activeToken();
    var response =
        postConsent(token, "{\"consentType\":\"MARKETING\",\"version\":\"1.1\",\"agreed\":true}");
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("LOCATION400_3");
  }

  @Test
  void rejectsStaleNoticeVersion() throws Exception {
    String token = activeToken();
    var response = postConsent(token, consentBody(true, "0.9"));
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("LOCATION409_1");
    assertThat(mapper.readTree(response.body()).path("message").asText())
        .isEqualTo("동의 안내문 버전이 변경됨");
  }

  @Test
  void requiresAuthenticationAndRejectsPendingOnboardingUser() throws Exception {
    assertThat(postConsent(null, consentBody(true, "1.1")).statusCode()).isEqualTo(401);

    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    var response = postConsent(signedIn.path("accessToken").asText(), consentBody(true, "1.1"));
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_2");
  }
}
