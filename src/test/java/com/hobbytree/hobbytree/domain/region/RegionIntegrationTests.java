package com.hobbytree.hobbytree.domain.region;

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

// regions is static reference data seeded once by V8 (행정안전부 법정동코드 snapshot), not
// per-test fixtures, so these tests assert against known real rows (종로구/강남구/중구 etc.)
// instead of creating their own - there is nothing to clean up afterwards.
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class RegionIntegrationTests extends IntegrationTestSupport {

  String activeToken() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    return signedIn.path("accessToken").asText();
  }

  HttpResponse<String> search(String token, String query) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/regions/search" + query))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  List<String> fieldNames(JsonNode node) {
    return new ArrayList<>(node.propertyNames());
  }

  List<Long> idsOf(HttpResponse<String> response) throws Exception {
    var ids = new ArrayList<Long>();
    result(response).path("items").forEach(i -> ids.add(i.path("regionId").asLong()));
    return ids;
  }

  @Test
  void searchesSigunguByNameOrFullNameAndReturnsTheExactContract() throws Exception {
    String token = activeToken();

    var response = search(token, "?keyword=종로");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var envelope = mapper.readTree(response.body());
    assertThat(fieldNames(envelope))
        .containsExactlyInAnyOrder("isSuccess", "code", "message", "result");
    assertThat(envelope.path("code").asText()).isEqualTo("LOCATION200_1");
    assertThat(envelope.path("message").asText()).isEqualTo("지역 검색 결과를 조회했습니다.");
    var result = envelope.path("result");
    assertThat(fieldNames(result)).containsExactlyInAnyOrder("items", "nextCursor", "hasNext");
    assertThat(idsOf(response)).containsExactly(11110L);
    var item = result.path("items").get(0);
    assertThat(fieldNames(item))
        .containsExactlyInAnyOrder("regionId", "name", "fullName", "level", "parentRegionId");
    assertThat(item.path("name").asText()).isEqualTo("종로구");
    assertThat(item.path("fullName").asText()).isEqualTo("서울특별시 종로구");
    assertThat(item.path("level").asText()).isEqualTo("SIGUNGU");
    assertThat(item.path("parentRegionId").asLong()).isEqualTo(11000L);

    // "강남" alone doesn't appear in the name "강남구", only via the fullName
    // "서울특별시 강남구" - proves the match checks fullName too, not just name.
    assertThat(idsOf(search(token, "?keyword=서울특별시%20강남"))).containsExactly(11680L);
  }

  @Test
  void searchesDongLevelScopedToParentRegionId() throws Exception {
    String token = activeToken();
    // "가회" matches two unrelated dong-level rows nationwide: 종로구's 가회동(1111014600)
    // and 합천군's 가회면(4889044000). Without a parent filter both come back; adding
    // parentRegionId must narrow it down to just the one under 종로구(11110).
    assertThat(idsOf(search(token, "?keyword=가회&level=DONG")))
        .containsExactlyInAnyOrder(1111014600L, 4889044000L);

    var scoped = search(token, "?keyword=가회&level=DONG&parentRegionId=11110");
    assertThat(idsOf(scoped)).containsExactly(1111014600L);
    var item = result(scoped).path("items").get(0);
    assertThat(item.path("level").asText()).isEqualTo("DONG");
    assertThat(item.path("parentRegionId").asLong()).isEqualTo(11110L);

    var broader = search(token, "?keyword=동&level=DONG&parentRegionId=11110&size=50");
    var items = result(broader).path("items");
    assertThat(items.size()).isGreaterThan(0);
    items.forEach(i -> assertThat(i.path("parentRegionId").asLong()).isEqualTo(11110L));
  }

  @Test
  void paginatesStablyWithCursorAcrossTiedNames() throws Exception {
    String token = activeToken();
    // All six 중구 rows share the exact name "중구", so pagination must fall back to
    // regionId as the tiebreak - otherwise this page sequence would duplicate or skip rows.
    var page1 = search(token, "?keyword=중구&size=2");
    assertThat(idsOf(page1)).containsExactly(11140L, 26110L);
    assertThat(result(page1).path("hasNext").asBoolean()).isTrue();
    String cursor1 = result(page1).path("nextCursor").asText();

    var page2 = search(token, "?keyword=중구&size=2&cursor=" + cursor1);
    assertThat(idsOf(page2)).containsExactly(27110L, 28110L);
    assertThat(result(page2).path("hasNext").asBoolean()).isTrue();
    String cursor2 = result(page2).path("nextCursor").asText();

    var page3 = search(token, "?keyword=중구&size=2&cursor=" + cursor2);
    assertThat(idsOf(page3)).containsExactly(30140L, 31110L);
    assertThat(result(page3).path("hasNext").asBoolean()).isFalse();
    assertThat(result(page3).path("nextCursor").isNull()).isTrue();
  }

  @Test
  void rejectsUnsupportedLevelAndMalformedCursor() throws Exception {
    String token = activeToken();
    for (String query :
        List.of(
            "?keyword=종로&level=SIDO", "?keyword=종로&level=FOO", "?keyword=종로&cursor=not-valid!!")) {
      var response = search(token, query);
      assertThat(response.statusCode()).as(query).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(query)
          .isEqualTo("LOCATION400_1");
    }
  }

  @Test
  void rejectsBlankOrOverlongKeyword() throws Exception {
    String token = activeToken();
    for (String query : List.of("?keyword=%20%20%20", "?keyword=" + "a".repeat(51))) {
      var response = search(token, query);
      assertThat(response.statusCode()).as(query).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(query)
          .isEqualTo("LOCATION400_1");
    }
  }

  @Test
  void escapesLikeWildcardsInsteadOfMatchingEveryRow() throws Exception {
    String token = activeToken();
    // "%" and "_" are LIKE wildcards; searched for literally, no region name contains either
    // character, so both must come back empty rather than matching all ~5,300 rows.
    assertThat(idsOf(search(token, "?keyword=%25"))).isEmpty();
    assertThat(idsOf(search(token, "?keyword=_"))).isEmpty();
  }

  @Test
  void rejectsMissingKeywordAndOutOfRangeSize() throws Exception {
    String token = activeToken();
    var missingKeyword = search(token, "");
    assertThat(missingKeyword.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(missingKeyword.body()).path("code").asText())
        .isEqualTo("COMMON400_1");
    for (String query : List.of("?keyword=종로&size=0", "?keyword=종로&size=51")) {
      var response = search(token, query);
      assertThat(response.statusCode()).as(query).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(query)
          .isEqualTo("COMMON400_1");
    }
  }

  @Test
  void requiresAuthenticationAndRejectsPendingOnboardingUser() throws Exception {
    assertThat(search(null, "?keyword=종로").statusCode()).isEqualTo(401);

    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    var response = search(signedIn.path("accessToken").asText(), "?keyword=종로");
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_2");
  }
}
