package com.hobbytree.hobbytree.domain.location;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.location.code.LocationErrorCode;
import com.hobbytree.hobbytree.domain.location.service.KakaoCoordinateRegionResolver;
import com.hobbytree.hobbytree.global.exception.ProjectException;
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
// class's @AfterEach cleanup (which deletes each createdUsers id) is enough - nothing to
// override here, unlike Hobby/SponsoredContent's non-cascading FKs.
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class UserLocationIntegrationTests extends IntegrationTestSupport {
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

  void consent(long userId, boolean agreed, String version) {
    jdbc.update(
        "INSERT INTO location_consents(user_id,consent_type,version,agreed) VALUES"
            + " (?,'NEARBY_EXPERIENCES',?,?)",
        userId,
        version,
        agreed);
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

  String manualBody(long regionId) {
    return "{\"source\":\"MANUAL\",\"regionId\":%d}".formatted(regionId);
  }

  String deviceBody(double lat, double lng, String consentVersion) {
    return "{\"source\":\"DEVICE\",\"latitude\":%s,\"longitude\":%s,\"consentVersion\":\"%s\"}"
        .formatted(lat, lng, consentVersion);
  }

  List<String> fieldNames(JsonNode node) {
    return new ArrayList<>(node.propertyNames());
  }

  @Test
  void setsManualLocationAndReturnsTheExactContract() throws Exception {
    String token = activeToken();

    var response = putLocation(token, manualBody(11110L));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var envelope = mapper.readTree(response.body());
    assertThat(fieldNames(envelope))
        .containsExactlyInAnyOrder("isSuccess", "code", "message", "result");
    assertThat(envelope.path("code").asText()).isEqualTo("LOCATION200_2");
    assertThat(envelope.path("message").asText()).isEqualTo("활동 지역이 설정되었습니다.");
    var result = envelope.path("result");
    assertThat(fieldNames(result)).containsExactlyInAnyOrder("userId", "location");
    var location = result.path("location");
    assertThat(fieldNames(location))
        .containsExactlyInAnyOrder(
            "regionId", "name", "fullName", "level", "parentRegionId", "source", "updatedAt");
    assertThat(location.path("regionId").asLong()).isEqualTo(11110L);
    assertThat(location.path("name").asText()).isEqualTo("종로구");
    assertThat(location.path("fullName").asText()).isEqualTo("서울특별시 종로구");
    assertThat(location.path("level").asText()).isEqualTo("SIGUNGU");
    assertThat(location.path("parentRegionId").asLong()).isEqualTo(11000L);
    assertThat(location.path("source").asText()).isEqualTo("MANUAL");
    assertThat(location.path("updatedAt").asText()).isNotBlank();
  }

  @Test
  void replacesExistingLocationOnASecondCall() throws Exception {
    String token = activeToken();
    putLocation(token, manualBody(11110L));

    var second = putLocation(token, manualBody(11680L));
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(result(second).path("location").path("regionId").asLong()).isEqualTo(11680L);

    // Only one row per user - not two accumulating rows.
    long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM user_locations WHERE user_id=?", Long.class, currentUserId());
    assertThat(count).isEqualTo(1);
  }

  @Test
  void rejectsManualRequestsMissingRegionIdOrMixingDeviceFields() throws Exception {
    String token = activeToken();
    for (String body :
        List.of(
            "{\"source\":\"MANUAL\"}",
            "{\"source\":\"MANUAL\",\"regionId\":11110,\"latitude\":37.5}",
            "{\"source\":\"MANUAL\",\"regionId\":11110,\"consentVersion\":\"1.1\"}")) {
      var response = putLocation(token, body);
      assertThat(response.statusCode()).as(body).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(body)
          .isEqualTo("LOCATION400_2");
    }
  }

  @Test
  void setsDeviceLocationAfterValidConsentUsingTheKakaoResolver() throws Exception {
    String token = activeToken();
    consent(currentUserId(), true, "1.1");
    when(kakaoResolver.resolveSigunguRegionId(37.57, 126.97)).thenReturn(11680L);

    var response = putLocation(token, deviceBody(37.57, 126.97, "1.1"));
    assertThat(response.statusCode()).isEqualTo(200);
    var location = result(response).path("location");
    assertThat(location.path("regionId").asLong()).isEqualTo(11680L);
    assertThat(location.path("source").asText()).isEqualTo("DEVICE");
    verify(kakaoResolver).resolveSigunguRegionId(37.57, 126.97);
  }

  @Test
  void rejectsDeviceRequestWithoutConsentOrWithRevokedConsent() throws Exception {
    String token = activeToken();
    var noConsent = putLocation(token, deviceBody(37.57, 126.97, "1.1"));
    assertThat(noConsent.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(noConsent.body()).path("code").asText()).isEqualTo("LOCATION403_1");

    consent(currentUserId(), false, "1.1");
    var revoked = putLocation(token, deviceBody(37.57, 126.97, "1.1"));
    assertThat(revoked.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(revoked.body()).path("code").asText()).isEqualTo("LOCATION403_1");
  }

  @Test
  void rejectsDeviceRequestWithMismatchedConsentVersion() throws Exception {
    String token = activeToken();
    consent(currentUserId(), true, "0.9");

    var response = putLocation(token, deviceBody(37.57, 126.97, "1.1"));
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("LOCATION409_1");
  }

  @Test
  void rejectsDeviceRequestsWithOutOfRangeCoordinatesOrMixedFields() throws Exception {
    String token = activeToken();
    consent(currentUserId(), true, "1.1");
    for (String body :
        List.of(
            "{\"source\":\"DEVICE\",\"latitude\":91,\"longitude\":126.97,\"consentVersion\":\"1.1\"}",
            "{\"source\":\"DEVICE\",\"latitude\":37.57,\"longitude\":-181,\"consentVersion\":\"1.1\"}",
            "{\"source\":\"DEVICE\",\"latitude\":37.57,\"longitude\":126.97,\"consentVersion\":\"1.1\",\"regionId\":11110}")) {
      var response = putLocation(token, body);
      assertThat(response.statusCode()).as(body).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(body)
          .isEqualTo("LOCATION400_2");
    }
  }

  @Test
  void rejectsWhenKakaoConversionFails() throws Exception {
    String token = activeToken();
    consent(currentUserId(), true, "1.1");
    when(kakaoResolver.resolveSigunguRegionId(anyDouble(), anyDouble()))
        .thenThrow(new ProjectException(LocationErrorCode.CONVERSION_UNAVAILABLE));

    var response = putLocation(token, deviceBody(37.57, 126.97, "1.1"));
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("LOCATION503_1");
  }

  @Test
  void rejectsWhenResolvedRegionIsNotSigunguLevel() throws Exception {
    String token = activeToken();
    consent(currentUserId(), true, "1.1");
    // 1111014600 is 가회동, a DONG-level row, not a settable 활동 지역.
    when(kakaoResolver.resolveSigunguRegionId(anyDouble(), anyDouble())).thenReturn(1111014600L);

    var response = putLocation(token, deviceBody(37.57, 126.97, "1.1"));
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("LOCATION404_1");
  }

  @Test
  void rejectsUnsupportedSourceValue() throws Exception {
    String token = activeToken();
    var response = putLocation(token, "{\"source\":\"GPS\",\"regionId\":11110}");
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("LOCATION400_2");
  }

  @Test
  void includesLocationAndLocationConsentInMyProfile() throws Exception {
    String token = activeToken();

    var beforeAnything = getMyProfile(token);
    var beforeResult = result(beforeAnything);
    assertThat(beforeResult.path("location").isNull()).isTrue();
    var defaultConsent = beforeResult.path("locationConsent");
    assertThat(defaultConsent.path("consentType").asText()).isEqualTo("NEARBY_EXPERIENCES");
    assertThat(defaultConsent.path("agreed").asBoolean()).isFalse();
    assertThat(defaultConsent.path("recordedAt").isNull()).isTrue();

    putLocation(token, manualBody(11110L));
    consent(currentUserId(), true, "1.1");

    var after = result(getMyProfile(token));
    assertThat(after.path("location").path("regionId").asLong()).isEqualTo(11110L);
    assertThat(after.path("locationConsent").path("agreed").asBoolean()).isTrue();
    assertThat(after.path("locationConsent").path("version").asText()).isEqualTo("1.1");
    assertThat(after.path("locationConsent").path("recordedAt").isNull()).isFalse();
  }

  @Test
  void requiresAuthenticationAndRejectsPendingOnboardingUser() throws Exception {
    assertThat(putLocation(null, manualBody(11110L)).statusCode()).isEqualTo(401);

    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    var response = putLocation(signedIn.path("accessToken").asText(), manualBody(11110L));
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_2");
  }
}
