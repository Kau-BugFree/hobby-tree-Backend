package com.hobbytree.hobbytree.domain.onboarding;

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
class OnboardingIntegrationTests extends IntegrationTestSupport {

  HttpResponse<String> getOptions(String token) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/onboarding/options"))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  List<String> fieldNames(JsonNode node) {
    return new ArrayList<>(node.propertyNames());
  }

  @Test
  void returnsOnboardingOptionsWithoutAuthentication() throws Exception {
    var response = getOptions(null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var envelope = mapper.readTree(response.body());
    assertThat(envelope.path("code").asText()).isEqualTo("ONBOARDING200_1");
    assertThat(envelope.path("message").asText()).isEqualTo("온보딩 선택지를 조회했습니다.");

    var result = envelope.path("result");
    assertThat(fieldNames(result))
        .containsExactlyInAnyOrder(
            "optionsVersion",
            "budgetOptions",
            "availableTimeOptions",
            "indoorOutdoorOptions",
            "difficultyOptions",
            "categories",
            "terms",
            "locationConsentNotice");
    assertThat(result.path("optionsVersion").asText()).isNotBlank();

    var budget = result.path("budgetOptions");
    assertThat(budget).hasSize(3);
    assertThat(fieldNames(budget.get(0)))
        .containsExactlyInAnyOrder("value", "label", "monthlyMin", "monthlyMax");
    assertThat(budget.get(0).path("value").asText()).isEqualTo("LOW");
    assertThat(budget.get(0).path("monthlyMin").asInt()).isEqualTo(0);
    assertThat(budget.get(0).path("monthlyMax").asInt()).isEqualTo(30000);
    assertThat(budget.get(2).path("value").asText()).isEqualTo("HIGH");
    assertThat(budget.get(2).path("monthlyMax").isNull()).isTrue();

    var time = result.path("availableTimeOptions");
    assertThat(time).hasSize(4);
    assertThat(fieldNames(time.get(0))).containsExactlyInAnyOrder("value", "label");
    assertThat(time.get(0).path("value").asInt()).isEqualTo(15);

    var indoorOutdoor = result.path("indoorOutdoorOptions");
    assertThat(indoorOutdoor).hasSize(3);
    var indoorOutdoorValues = new ArrayList<String>();
    indoorOutdoor.forEach(o -> indoorOutdoorValues.add(o.path("value").asText()));
    assertThat(indoorOutdoorValues).containsExactly("INDOOR", "OUTDOOR", "ANY");

    var difficulty = result.path("difficultyOptions");
    assertThat(difficulty).hasSize(3);
    var difficultyValues = new ArrayList<String>();
    difficulty.forEach(o -> difficultyValues.add(o.path("value").asText()));
    assertThat(difficultyValues).containsExactly("BEGINNER", "INTERMEDIATE", "ANY");

    var categories = result.path("categories");
    assertThat(categories).hasSize(3);
    assertThat(fieldNames(categories.get(0))).containsExactlyInAnyOrder("categoryId", "name");
    var categoryNames = new ArrayList<String>();
    categories.forEach(c -> categoryNames.add(c.path("name").asText()));
    assertThat(categoryNames).containsExactly("요리", "악기", "독서");
    assertThat(categories.get(0).path("categoryId").asInt()).isEqualTo(1);

    var terms = result.path("terms");
    assertThat(terms).hasSize(2);
    assertThat(fieldNames(terms.get(0)))
        .containsExactlyInAnyOrder("termsId", "title", "version", "isRequired", "contentUrl");
    assertThat(terms.get(0).path("isRequired").asBoolean()).isTrue();
    assertThat(terms.get(0).path("contentUrl").asText()).isNotBlank();

    var notice = result.path("locationConsentNotice");
    assertThat(fieldNames(notice))
        .containsExactlyInAnyOrder("consentType", "version", "contentUrl");
    assertThat(notice.path("consentType").asText()).isEqualTo("NEARBY_EXPERIENCES");
    // Must match LocationConsentNotice.CURRENT_VERSION, the same constant API-42/43 compare
    // against - not just independently equal to "1.0".
    assertThat(notice.path("version").asText())
        .isEqualTo(
            com.hobbytree.hobbytree.domain.location.entity.LocationConsentNotice.CURRENT_VERSION);
  }

  @Test
  void aStaleOrInvalidAuthorizationHeaderStillRejectsThisPublicEndpoint() throws Exception {
    // Documents a known, separately-tracked gap (hobbytree-auth-known-issues #1): AuthFilter
    // validates the token whenever an Authorization header is present, running before
    // SecurityConfig's permitAll ever gets a say - so a client with a stale/invalid token
    // attached (e.g. a frontend interceptor that always sends one) gets wrongly rejected even
    // on this permitAll path. The real public-access path (no header at all) works, see
    // returnsOnboardingOptionsWithoutAuthentication. Not fixed here; this test only pins the
    // current behavior so a future fix to that issue is a deliberate, visible change here.
    var response = getOptions("not-a-valid-token");
    assertThat(response.statusCode()).isEqualTo(401);
  }
}
