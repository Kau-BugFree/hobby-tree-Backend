package com.hobbytree.hobbytree.domain.sponsoredcontent;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class SponsoredContentIntegrationTests extends IntegrationTestSupport {
  Long hobbyId;
  Long stageId;

  long activity(String status) {
    if (hobbyId == null) {
      hobbyId =
          jdbc.queryForObject(
              "INSERT INTO hobbies(hobby_code,hobby_name) VALUES (?, 'Sponsored test') RETURNING hobby_id",
              Long.class,
              "sponsored-" + UUID.randomUUID());
      stageId =
          jdbc.queryForObject(
              "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,0,'Seed') RETURNING hobby_stage_id",
              Long.class,
              hobbyId);
    }
    return jdbc.queryForObject(
        "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,review_status) VALUES (?,?,'Sponsored test',?) RETURNING activity_id",
        Long.class,
        stageId,
        "sponsored-" + UUID.randomUUID(),
        status);
  }

  long sponsoredContent(String status, Instant startsAt, Instant endsAt) {
    // description (not title) carries the unique marker for cleanup, so title can match the
    // API.md example exactly and be asserted on directly.
    return jdbc.queryForObject(
        "INSERT INTO sponsored_contents(title,description,sponsor_name,thumbnail_url,content_type,"
            + "disclosure_label,external_url,status,starts_at,ends_at) VALUES"
            + " (?,?,?,?,'PRODUCT','광고·제휴',?,?,?,?) RETURNING sponsored_content_id",
        Long.class,
        "입문용 드로잉 키트",
        "sponsored-test-" + UUID.randomUUID(),
        "예시 파트너",
        "https://cdn.example.com/sponsored/drawing-kit.png",
        "https://partner.example.com/products/drawing-kit",
        status,
        startsAt == null ? null : Timestamp.from(startsAt),
        endsAt == null ? null : Timestamp.from(endsAt));
  }

  void mapToActivity(long activityId, long sponsoredContentId, int sortOrder) {
    jdbc.update(
        "INSERT INTO activity_sponsored_contents(activity_id,sponsored_content_id,sort_order)"
            + " VALUES (?,?,?)",
        activityId,
        sponsoredContentId,
        sortOrder);
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    if (stageId != null) {
      jdbc.update("DELETE FROM activities WHERE hobby_stage_id=?", stageId);
      jdbc.update("DELETE FROM hobby_stages WHERE hobby_stage_id=?", stageId);
      jdbc.update("DELETE FROM hobbies WHERE hobby_id=?", hobbyId);
    }
    jdbc.update("DELETE FROM sponsored_contents WHERE description LIKE 'sponsored-test-%'");
    super.cleanupSeededHobbies(userId);
  }

  String activeToken() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return signedIn.path("accessToken").asText();
  }

  HttpResponse<String> getSponsoredContent(String token, long activityId) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/v1/activities/"
                        + activityId
                        + "/sponsored-content"))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void returnsVisibleContentOrderedBySortOrderAndExcludesInactiveOrOutOfWindow() throws Exception {
    String token = activeToken();
    long activityId = activity("PUBLISHED");
    long second = sponsoredContent("ACTIVE", null, null);
    long first = sponsoredContent("ACTIVE", null, null);
    long inactive = sponsoredContent("INACTIVE", null, null);
    long notStartedYet = sponsoredContent("ACTIVE", Instant.now().plus(Duration.ofDays(1)), null);
    long alreadyEnded = sponsoredContent("ACTIVE", null, Instant.now().minus(Duration.ofDays(1)));
    mapToActivity(activityId, second, 2);
    mapToActivity(activityId, first, 1);
    mapToActivity(activityId, inactive, 3);
    mapToActivity(activityId, notStartedYet, 4);
    mapToActivity(activityId, alreadyEnded, 5);

    var response = getSponsoredContent(token, activityId);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
    var envelope = mapper.readTree(response.body());
    // Lock the exact wire contract a frontend client codes against: no missing/extra fields,
    // and the Korean message text travels intact (not mangled) over the wire.
    assertThat(fieldNames(envelope))
        .containsExactlyInAnyOrder("isSuccess", "code", "message", "result");
    assertThat(envelope.path("isSuccess").asBoolean()).isTrue();
    assertThat(envelope.path("code").asText()).isEqualTo("SPONSORED200_1");
    assertThat(envelope.path("message").asText()).isEqualTo("활동 관련 제휴 콘텐츠를 조회했습니다.");
    var result = envelope.path("result");
    assertThat(fieldNames(result)).containsExactlyInAnyOrder("activityId", "items");
    assertThat(result.path("activityId").asLong()).isEqualTo(activityId);
    var items = result.path("items");
    assertThat(items.isArray()).isTrue();
    assertThat(items.size()).isEqualTo(2);
    assertThat(items.get(0).path("sponsoredContentId").asLong()).isEqualTo(first);
    assertThat(items.get(1).path("sponsoredContentId").asLong()).isEqualTo(second);
    var item = items.get(0);
    assertThat(fieldNames(item))
        .containsExactlyInAnyOrder(
            "sponsoredContentId",
            "title",
            "description",
            "sponsorName",
            "thumbnailUrl",
            "contentType",
            "isSponsored",
            "disclosureLabel",
            "externalUrl",
            "sortOrder");
    assertThat(item.path("title").asText()).isEqualTo("입문용 드로잉 키트");
    assertThat(item.path("sponsorName").asText()).isEqualTo("예시 파트너");
    assertThat(item.path("thumbnailUrl").asText())
        .isEqualTo("https://cdn.example.com/sponsored/drawing-kit.png");
    assertThat(item.path("contentType").asText()).isEqualTo("PRODUCT");
    assertThat(item.path("isSponsored").asBoolean()).isTrue();
    assertThat(item.path("disclosureLabel").asText()).isEqualTo("광고·제휴");
    assertThat(item.path("externalUrl").asText())
        .isEqualTo("https://partner.example.com/products/drawing-kit");
    assertThat(item.path("sortOrder").asInt()).isEqualTo(1);
  }

  List<String> fieldNames(tools.jackson.databind.JsonNode node) {
    return new java.util.ArrayList<>(node.propertyNames());
  }

  @Test
  void returnsEmptyArrayWhenNoContentIsMapped() throws Exception {
    String token = activeToken();
    long activityId = activity("PUBLISHED");
    var response = getSponsoredContent(token, activityId);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(result(response).path("items").isArray()).isTrue();
    assertThat(result(response).path("items")).isEmpty();
  }

  @Test
  void rejectsMissingOrUnpublishedActivity() throws Exception {
    String token = activeToken();
    for (String state : List.of("DRAFT", "REVIEW", "REJECTED")) {
      var response = getSponsoredContent(token, activity(state));
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("ACTIVITY404_1");
    }
    assertThat(getSponsoredContent(token, 9223372036854775807L).statusCode()).isEqualTo(404);
  }

  @Test
  void requiresAuthenticationAndRejectsPendingOnboardingUser() throws Exception {
    long activityId = activity("PUBLISHED");
    assertThat(getSponsoredContent(null, activityId).statusCode()).isEqualTo(401);

    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    var response = getSponsoredContent(signedIn.path("accessToken").asText(), activityId);
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_2");
  }

  @Test
  void externalUrlRequiresAHostNotJustTheHttpsScheme() {
    // 'https://' and 'https:///path' both satisfied the old LIKE 'https://%' check but have
    // no host for the query API to ever return as an openable link.
    for (String hostless : List.of("https://", "https:///products/drawing-kit")) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "INSERT INTO sponsored_contents(title,sponsor_name,content_type,external_url)"
                          + " VALUES ('t','s','PRODUCT',?)",
                      hostless))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
  }
}
