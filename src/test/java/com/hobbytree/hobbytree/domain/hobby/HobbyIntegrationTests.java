package com.hobbytree.hobbytree.domain.hobby;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class HobbyIntegrationTests extends IntegrationTestSupport {
  private final List<Long> createdHobbyIds = new ArrayList<>();

  long hobby(String category, String indoorOutdoor, boolean supported) {
    long hobbyId =
        jdbc.queryForObject(
            "INSERT INTO hobbies(hobby_code,hobby_name,description,category,indoor_outdoor,is_supported)"
                + " VALUES (?,?,?,?,?,?) RETURNING hobby_id",
            Long.class,
            "hbt-" + UUID.randomUUID(),
            "하비_" + UUID.randomUUID(),
            "테스트용 취미",
            category,
            indoorOutdoor,
            supported);
    createdHobbyIds.add(hobbyId);
    return hobbyId;
  }

  long stage(long hobbyId, int stageNumber) {
    return jdbc.queryForObject(
        "INSERT INTO hobby_stages(hobby_id,stage_number,stage_name) VALUES (?,?,'Seed') RETURNING hobby_stage_id",
        Long.class,
        hobbyId,
        stageNumber);
  }

  void introActivity(long stageId, int durationMinutes, int cost, String difficulty) {
    jdbc.update(
        "INSERT INTO activities(hobby_stage_id,activity_code,activity_name,expected_total_minutes,"
            + "estimated_cost_min,difficulty,review_status) VALUES (?,?,'Intro',?,?,?,'PUBLISHED')",
        stageId,
        "hobby-browse-test-" + UUID.randomUUID(),
        durationMinutes,
        cost,
        difficulty);
  }

  long plant(long userId, long hobbyId) {
    return jdbc.queryForObject(
        "INSERT INTO user_hobbies(user_id,hobby_id,tree_status) VALUES (?,?,'ACTIVE') RETURNING user_hobby_id",
        Long.class,
        userId,
        hobbyId);
  }

  // Scopes a test's result-set assertions to only the hobbies it created, since other
  // supported hobbies can legitimately exist in the DB (other tests, leftover fixtures) and
  // would otherwise leak into a containsExactly() assertion.
  String uniqueMarker() {
    return "hbt" + UUID.randomUUID().toString().replace("-", "");
  }

  void markHobby(long hobbyId, String marker) {
    jdbc.update(
        "UPDATE hobbies SET hobby_name = ? || hobby_name WHERE hobby_id=?", marker, hobbyId);
  }

  long otherUser() {
    long userId =
        jdbc.queryForObject(
            "INSERT INTO users(provider,provider_user_id,account_status) VALUES"
                + " ('KAKAO',?,'ACTIVE') RETURNING user_id",
            Long.class,
            "hobby-browse-test-" + UUID.randomUUID());
    createdUsers.add(userId);
    return userId;
  }

  @Override
  protected void cleanupSeededHobbies(long userId) {
    for (Long hobbyId : createdHobbyIds) {
      jdbc.update("DELETE FROM user_hobbies WHERE hobby_id=?", hobbyId);
      jdbc.update(
          "DELETE FROM activities WHERE hobby_stage_id IN"
              + " (SELECT hobby_stage_id FROM hobby_stages WHERE hobby_id=?)",
          hobbyId);
      jdbc.update("DELETE FROM hobby_stages WHERE hobby_id=?", hobbyId);
      jdbc.update("DELETE FROM hobbies WHERE hobby_id=?", hobbyId);
    }
    super.cleanupSeededHobbies(userId);
  }

  String activeToken() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return signedIn.path("accessToken").asText();
  }

  HttpResponse<String> listHobbies(String token, String query) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/hobbies" + query))
            .timeout(java.time.Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  List<String> fieldNames(JsonNode node) {
    return new ArrayList<>(node.propertyNames());
  }

  @Test
  void returnsVisibleHobbiesWithIntroSummaryAndMarksOwnedHobbyAsPlanted() throws Exception {
    String token = activeToken();
    long id =
        jdbc.queryForObject(
            "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);

    long visible = hobby("COOKING", "INDOOR", true);
    introActivity(stage(visible, 0), 20, 5000, "BEGINNER");
    long userHobbyId = plant(id, visible);

    long hidden = hobby("INSTRUMENT", "OUTDOOR", false);
    introActivity(stage(hidden, 0), 10, 0, "BEGINNER");

    var response = listHobbies(token, "?size=20");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var envelope = mapper.readTree(response.body());
    assertThat(fieldNames(envelope))
        .containsExactlyInAnyOrder("isSuccess", "code", "message", "result");
    assertThat(envelope.path("code").asText()).isEqualTo("HOBBY200_1");
    assertThat(envelope.path("message").asText()).isEqualTo("취미 목록을 조회했습니다.");
    var result = envelope.path("result");
    assertThat(fieldNames(result)).containsExactlyInAnyOrder("items", "nextCursor", "hasNext");

    var items = result.path("items");
    var ids = new ArrayList<Long>();
    items.forEach(i -> ids.add(i.path("hobbyId").asLong()));
    assertThat(ids).contains(visible).doesNotContain(hidden);

    var item = items.get(ids.indexOf(visible));
    assertThat(fieldNames(item))
        .containsExactlyInAnyOrder(
            "hobbyId",
            "name",
            "description",
            "category",
            "thumbnailUrl",
            "minDurationMinutes",
            "minCost",
            "difficulty",
            "indoorOutdoor",
            "isPlanted",
            "userHobbyId");
    assertThat(fieldNames(item.path("category"))).containsExactlyInAnyOrder("categoryId", "name");
    assertThat(item.path("category").path("categoryId").asInt()).isEqualTo(1);
    assertThat(item.path("category").path("name").asText()).isEqualTo("요리");
    assertThat(item.path("minDurationMinutes").asInt()).isEqualTo(20);
    assertThat(item.path("minCost").asInt()).isEqualTo(5000);
    assertThat(item.path("difficulty").asText()).isEqualTo("BEGINNER");
    assertThat(item.path("indoorOutdoor").asText()).isEqualTo("INDOOR");
    assertThat(item.path("isPlanted").asBoolean()).isTrue();
    assertThat(item.path("userHobbyId").asLong()).isEqualTo(userHobbyId);
  }

  @Test
  void filtersByKeywordCategoryIndoorOutdoorCostAndDuration() throws Exception {
    String token = activeToken();
    String marker = uniqueMarker();
    long ukulele = hobby("INSTRUMENT", "OUTDOOR", true);
    jdbc.update("UPDATE hobbies SET hobby_name=? WHERE hobby_id=?", marker + "우쿨렐레 입문", ukulele);
    introActivity(stage(ukulele, 0), 60, 30000, "INTERMEDIATE");

    long reading = hobby("READING", "INDOOR", true);
    jdbc.update("UPDATE hobbies SET hobby_name=? WHERE hobby_id=?", marker + "독서 모임", reading);
    introActivity(stage(reading, 0), 10, 0, "BEGINNER");

    // Every request also carries `keyword=marker` so results stay scoped to just these two
    // hobbies, no matter what other supported hobbies already exist in the DB.
    assertThat(idsOf(listHobbies(token, "?keyword=" + marker + "우쿨렐레"))).containsExactly(ukulele);
    assertThat(idsOf(listHobbies(token, "?keyword=" + marker + "&categoryId=3")))
        .containsExactly(reading);
    assertThat(idsOf(listHobbies(token, "?keyword=" + marker + "&indoorOutdoor=OUTDOOR")))
        .containsExactly(ukulele);
    assertThat(idsOf(listHobbies(token, "?keyword=" + marker + "&maxDurationMinutes=30")))
        .containsExactly(reading);
    assertThat(idsOf(listHobbies(token, "?keyword=" + marker + "&maxCost=100")))
        .containsExactly(reading);
  }

  List<Long> idsOf(HttpResponse<String> response) throws Exception {
    var ids = new ArrayList<Long>();
    result(response).path("items").forEach(i -> ids.add(i.path("hobbyId").asLong()));
    return ids;
  }

  @Test
  void sortsPopularDescendingWithCursorPagination() throws Exception {
    String token = activeToken();
    String marker = uniqueMarker();
    long mostPopular = hobby("COOKING", "INDOOR", true);
    markHobby(mostPopular, marker);
    long middle = hobby("COOKING", "INDOOR", true);
    markHobby(middle, marker);
    long leastPopular = hobby("COOKING", "INDOOR", true);
    markHobby(leastPopular, marker);
    for (int i = 0; i < 3; i++) plant(otherUser(), mostPopular);
    plant(otherUser(), middle);

    // keyword=marker scopes every page to just these three hobbies: otherwise any other
    // already-supported hobby in the DB could shift which id/hasNext this test expects.
    String query = "?sort=POPULAR&size=1&keyword=" + marker;
    var page1 = listHobbies(token, query);
    assertThat(idsOf(page1)).containsExactly(mostPopular);
    assertThat(result(page1).path("hasNext").asBoolean()).isTrue();
    String cursor1 = result(page1).path("nextCursor").asText();

    var page2 = listHobbies(token, query + "&cursor=" + cursor1);
    assertThat(idsOf(page2)).containsExactly(middle);
    assertThat(result(page2).path("hasNext").asBoolean()).isTrue();
    String cursor2 = result(page2).path("nextCursor").asText();

    var page3 = listHobbies(token, query + "&cursor=" + cursor2);
    assertThat(idsOf(page3)).containsExactly(leastPopular);
    assertThat(result(page3).path("hasNext").asBoolean()).isFalse();
    assertThat(result(page3).path("nextCursor").isNull()).isTrue();
  }

  @Test
  void rejectsUnsupportedFilterSortAndCursor() throws Exception {
    String token = activeToken();
    for (String query :
        List.of(
            "?categoryId=99",
            "?sort=BEST",
            "?indoorOutdoor=SIDEWAYS",
            "?cursor=not-a-valid-cursor!!")) {
      var response = listHobbies(token, query);
      assertThat(response.statusCode()).as(query).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(query)
          .isEqualTo("HOBBY400_1");
    }
  }

  @Test
  void rejectsOutOfRangeNumericParams() throws Exception {
    String token = activeToken();
    for (String query :
        List.of(
            "?maxDurationMinutes=0",
            "?maxDurationMinutes=1500",
            "?maxCost=-1",
            "?size=0",
            "?size=51",
            "?keyword=" + "a".repeat(51))) {
      var response = listHobbies(token, query);
      assertThat(response.statusCode()).as(query).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(query)
          .isEqualTo("COMMON400_1");
    }
  }

  @Test
  void requiresAuthenticationAndRejectsPendingOnboardingUser() throws Exception {
    assertThat(listHobbies(null, "").statusCode()).isEqualTo(401);

    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    var response = listHobbies(signedIn.path("accessToken").asText(), "");
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_2");
  }
}
