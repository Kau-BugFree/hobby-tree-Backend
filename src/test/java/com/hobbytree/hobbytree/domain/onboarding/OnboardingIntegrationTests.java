package com.hobbytree.hobbytree.domain.onboarding;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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

  HttpResponse<String> postComplete(String token, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/onboarding/complete"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> putPreferences(String token) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/users/me/preferences"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"optionsVersion":"2026-11-07","budgetLevel":"LOW","availableTimeMinutes":30,
                     "indoorOutdoor":"INDOOR","difficultyPreference":"BEGINNER",
                     "preferredCategoryIds":[1],"expectedVersion":0}
                    """));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  String agreementsBody(int preferencesVersion, boolean agreeTerm1, boolean agreeTerm2) {
    return """
        {"preferencesVersion":%d,"agreements":[
          {"termsId":1,"version":"1.0","agreed":%b},
          {"termsId":2,"version":"1.0","agreed":%b}
        ]}
        """
        .formatted(preferencesVersion, agreeTerm1, agreeTerm2);
  }

  // A fresh, still-PENDING_ONBOARDING signup, with a valid nickname and a saved preferences
  // version 1 - the two preconditions API-13 requires before it will even look at agreements.
  String onboardingReadyToken() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET nickname='즐거운수달' WHERE user_id=?", id);
    String token = signedIn.path("accessToken").asText();
    putPreferences(token);
    return token;
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

  @Test
  void completesOnboardingAndReturnsTheExactContract() throws Exception {
    String token = onboardingReadyToken();
    long id =
        jdbc.queryForObject(
            "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);

    var response = postComplete(token, agreementsBody(1, true, true));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var envelope = mapper.readTree(response.body());
    assertThat(envelope.path("code").asText()).isEqualTo("ONBOARDING200_2");
    assertThat(envelope.path("message").asText()).isEqualTo("온보딩이 완료되었습니다.");
    var result = envelope.path("result");
    assertThat(fieldNames(result))
        .containsExactlyInAnyOrder("userId", "memberStatus", "onboardingCompleted", "completedAt");
    assertThat(result.path("userId").asLong()).isEqualTo(id);
    assertThat(result.path("memberStatus").asText()).isEqualTo("ACTIVE");
    assertThat(result.path("onboardingCompleted").asBoolean()).isTrue();
    assertThat(result.path("completedAt").asText()).isNotBlank();

    assertThat(users.findById(id).orElseThrow().getAccountStatus().name()).isEqualTo("ACTIVE");
    long agreementRows =
        jdbc.queryForObject(
            "SELECT count(*) FROM user_terms_agreements WHERE user_id=?", Long.class, id);
    assertThat(agreementRows).isEqualTo(2);
  }

  @Test
  void reconfirmingAnAlreadyActiveAccountKeepsTheOriginalCompletedAt() throws Exception {
    String token = onboardingReadyToken();
    long id =
        jdbc.queryForObject(
            "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);
    postComplete(token, agreementsBody(1, true, true));
    // Read the persisted value directly, not the first HTTP response's completedAt:
    // completeOnboarding() sets onboardingCompletedAt directly in Java (not DB-generated, same
    // as User.withdraw()'s withdrawnAt), and converting a raw nanosecond-precision
    // clock.instant() into a microsecond-resolution timestamptz column can round rather than
    // truncate - so the first response's own (pre-write) value isn't guaranteed to equal what
    // actually got persisted. Comparing two post-write reads avoids that rounding-boundary
    // flake entirely.
    Instant firstCompletedAt =
        jdbc.queryForObject(
                "SELECT onboarding_completed_at FROM users WHERE user_id=?",
                java.sql.Timestamp.class,
                id)
            .toInstant();

    var second = result(postComplete(token, agreementsBody(1, true, true)));
    assertThat(second.path("memberStatus").asText()).isEqualTo("ACTIVE");
    assertThat(Instant.parse(second.path("completedAt").asText())).isEqualTo(firstCompletedAt);
  }

  @Test
  void rejectsWhenPreferencesAreNotSavedOrVersionMismatches() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET nickname='즐거운수달' WHERE user_id=?", id);
    String token = signedIn.path("accessToken").asText();

    var noPreferences = postComplete(token, agreementsBody(0, true, true));
    assertThat(noPreferences.statusCode()).isEqualTo(409);
    assertThat(mapper.readTree(noPreferences.body()).path("code").asText())
        .isEqualTo("ONBOARDING409_1");

    putPreferences(token);
    var wrongVersion = postComplete(token, agreementsBody(2, true, true));
    assertThat(wrongVersion.statusCode()).isEqualTo(409);
    assertThat(mapper.readTree(wrongVersion.body()).path("code").asText())
        .isEqualTo("ONBOARDING409_1");
  }

  @Test
  void rejectsWhenARequiredTermIsNotAgreed() throws Exception {
    String token = onboardingReadyToken();
    var response = postComplete(token, agreementsBody(1, true, false));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("ONBOARDING400_1");
  }

  @Test
  void rejectsWhenNicknameIsMissing() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String token = signedIn.path("accessToken").asText();
    putPreferences(token);

    var response = postComplete(token, agreementsBody(1, true, true));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("ONBOARDING400_1");
  }

  @Test
  void rejectsWhenAnAgreementVersionIsStale() throws Exception {
    String token = onboardingReadyToken();
    var response =
        postComplete(
            token,
            """
            {"preferencesVersion":1,"agreements":[
              {"termsId":1,"version":"0.9","agreed":true},
              {"termsId":2,"version":"1.0","agreed":true}
            ]}
            """);
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("ONBOARDING409_1");
  }

  @Test
  void rejectsMalformedAgreementsShape() throws Exception {
    String token = onboardingReadyToken();
    for (String invalidBody :
        List.of(
            // Duplicate termsId.
            """
            {"preferencesVersion":1,"agreements":[
              {"termsId":1,"version":"1.0","agreed":true},
              {"termsId":1,"version":"1.0","agreed":true}
            ]}
            """,
            // Unknown (inactive) termsId.
            """
            {"preferencesVersion":1,"agreements":[
              {"termsId":99,"version":"1.0","agreed":true}
            ]}
            """)) {
      var response = postComplete(token, invalidBody);
      assertThat(response.statusCode()).as(invalidBody).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(invalidBody)
          .isEqualTo("COMMON400_1");
    }
  }

  @Test
  void rejectsMissingRequiredFields() throws Exception {
    String token = onboardingReadyToken();
    for (String invalidBody :
        List.of(
            "{\"agreements\":[{\"termsId\":1,\"version\":\"1.0\",\"agreed\":true}]}",
            "{\"preferencesVersion\":1}",
            "{}")) {
      var response = postComplete(token, invalidBody);
      assertThat(response.statusCode()).as(invalidBody).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(invalidBody)
          .isEqualTo("COMMON400_1");
    }
  }

  @Test
  void requiresAuthenticationAndRejectsSuspendedOrWithdrawnMembers() throws Exception {
    assertThat(postComplete(null, agreementsBody(1, true, true)).statusCode()).isEqualTo(401);

    String token = onboardingReadyToken();
    long id =
        jdbc.queryForObject(
            "SELECT user_id FROM users WHERE provider_user_id=?", Long.class, socialId);

    for (String status : List.of("SUSPENDED", "WITHDRAWN")) {
      jdbc.update(
          "UPDATE users SET account_status=?, withdrawn_at=CASE WHEN ?='WITHDRAWN' THEN now()"
              + " ELSE NULL END, restorable_until=CASE WHEN ?='WITHDRAWN' THEN now()+interval"
              + " '30 days' ELSE NULL END, status_before_withdrawal=CASE WHEN ?='WITHDRAWN'"
              + " THEN 'PENDING_ONBOARDING' ELSE NULL END WHERE user_id=?",
          status,
          status,
          status,
          status,
          id);
      assertThat(postComplete(token, agreementsBody(1, true, true)).statusCode())
          .as(status)
          .isEqualTo(403);
    }
  }
}
