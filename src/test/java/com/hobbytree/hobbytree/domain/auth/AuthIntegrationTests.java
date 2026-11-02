package com.hobbytree.hobbytree.domain.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.HobbytreeApplication;
import com.hobbytree.hobbytree.domain.auth.config.AuthProperties;
import com.hobbytree.hobbytree.domain.auth.service.*;
import com.hobbytree.hobbytree.domain.user.entity.*;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@SpringBootTest(
    classes = HobbytreeApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTests {
  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired UserRepository users;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean SocialTokenVerifier verifier;
  @MockitoSpyBean RedisAuthStore store;

  @Autowired
  @Qualifier("serviceJwtDecoder")
  JwtDecoder decoder;

  @Autowired JwtEncoder encoder;
  @Autowired AuthProperties authProperties;
  Set<Long> createdUsers = ConcurrentHashMap.newKeySet();
  String socialId;
  final HttpClient client = HttpClient.newHttpClient();

  @BeforeEach
  void setup() {
    socialId = "integration-" + UUID.randomUUID();
    when(verifier.verify(any(), anyString())).thenReturn(socialId);
    clearRate();
  }

  void clearRate() {
    redis.delete(
        List.of(
            "auth:login-rate:" + RedisAuthStore.hash("127.0.0.1"),
            "auth:login-rate:" + RedisAuthStore.hash("0:0:0:0:0:0:0:1")));
  }

  @AfterEach
  void cleanup() {
    users
        .findByProviderAndProviderUserId(AuthProvider.KAKAO, socialId)
        .ifPresent(u -> createdUsers.add(u.getUserId()));
    for (Long id : createdUsers) {
      redis.delete(List.of("auth:session:" + id, "auth:restore:" + id));
      var usedKeys = redis.keys("auth:refresh-used:" + id + ":*");
      if (!usedKeys.isEmpty()) redis.delete(usedKeys);
      var issuedKeys = redis.keys("auth:refresh-issued:" + id + ":*");
      if (!issuedKeys.isEmpty()) redis.delete(issuedKeys);
      // Runs even if the test failed before reaching its own cleanup call; a no-op for tests
      // that never seeded hobbies. Must happen before deleting the user: user_hobbies has no
      // FK cascade from hobbies, and hobbies' FK blocks deleting a hobby still referenced by it.
      cleanupSeededHobbies(id);
      users.deleteById(id);
    }
    createdUsers.clear();
    clearRate();
  }

  HttpResponse<String> login(String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/social-login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> login() throws Exception {
    return login("{\"provider\":\"KAKAO\",\"providerToken\":\"integration-test-token\"}");
  }

  JsonNode result(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("result");
  }

  int protectedStatus(String token) throws Exception {
    return client
        .send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + port + "/api/v1/nonexistent-protected-resource"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString())
        .statusCode();
  }

  @Test
  void loginPersistsHashAndImmediatelyInvalidatesPreviousAccessToken() throws Exception {
    var first = login();
    assertThat(first.statusCode()).isEqualTo(200);
    var a = result(first);
    long id = a.path("userId").asLong();
    createdUsers.add(id);
    assertThat(a.path("isNewUser").asBoolean()).isTrue();
    assertThat(a.path("memberStatus").asText()).isEqualTo("PENDING_ONBOARDING");
    assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
    String access = a.path("accessToken").asText();
    String refresh = a.path("refreshToken").asText();
    assertThat(redis.opsForHash().get("auth:session:" + id, "refreshHash"))
        .isEqualTo(RedisAuthStore.hash(refresh));
    assertThat(redis.getExpire("auth:session:" + id)).isBetween(1209590L, 1209600L);
    assertThat(protectedStatus(access)).isEqualTo(404);
    assertThat(protectedStatus(refresh)).isEqualTo(401);
    var second = login();
    assertThat(result(second).path("isNewUser").asBoolean()).isFalse();
    assertThat(protectedStatus(access)).isEqualTo(401);
    assertThat(protectedStatus(result(second).path("accessToken").asText())).isEqualTo(404);
  }

  @Test
  void concurrentSignupCreatesOneAccountAndOneCurrentSession() throws Exception {
    try (var executor = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<Future<HttpResponse<String>>>();
      for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> login()));
      var responses = new ArrayList<HttpResponse<String>>();
      for (var future : futures) responses.add(future.get(30, TimeUnit.SECONDS));
      assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
      var ids = responses.stream().map(r -> result(r).path("userId").asLong()).distinct().toList();
      assertThat(ids).hasSize(1);
      createdUsers.addAll(ids);
      assertThat(responses.stream().filter(r -> result(r).path("isNewUser").asBoolean()).count())
          .isEqualTo(1);
      long usable = 0;
      for (var r : responses)
        if (protectedStatus(result(r).path("accessToken").asText()) == 404) usable++;
      assertThat(usable).isEqualTo(1);
    }
  }

  @Test
  void malformedRequestsAreLimitedBeforeProviderCalls() throws Exception {
    for (int i = 0; i < 20; i++) assertThat(login("{broken").statusCode()).isEqualTo(400);
    var blocked = login();
    assertThat(blocked.statusCode()).isEqualTo(429);
    assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
    verifyNoInteractions(verifier);
  }

  @Test
  void recoveryAndSuspensionNeverIssueLoginSessions() throws Exception {
    long id = result(login()).path("userId").asLong();
    createdUsers.add(id);
    redis.delete("auth:session:" + id);
    jdbc.update("UPDATE users SET account_status='SUSPENDED' WHERE user_id=?", id);
    assertThat(login().statusCode()).isEqualTo(403);
    assertThat(redis.hasKey("auth:session:" + id)).isFalse();
    jdbc.update(
        "UPDATE users SET account_status='WITHDRAWN',withdrawn_at=now(),restorable_until=now()+interval '720 hours',status_before_withdrawal='PENDING_ONBOARDING' WHERE user_id=?",
        id);
    var recovery = login();
    assertThat(recovery.statusCode()).isEqualTo(409);
    var guide = result(recovery);
    assertThat(guide.path("restorable").asBoolean()).isTrue();
    String token = guide.path("restoreToken").asText();
    assertThat(redis.opsForValue().get("auth:restore:" + id)).isEqualTo(RedisAuthStore.hash(token));
    assertThat(protectedStatus(token)).isEqualTo(401);
    jdbc.update(
        "UPDATE users SET withdrawn_at=now()-interval '721 hours',restorable_until=now()-interval '1 hour' WHERE user_id=?",
        id);
    var expired = login();
    assertThat(result(expired).path("restorable").asBoolean()).isFalse();
    assertThat(result(expired).path("restoreToken").isNull()).isTrue();
    assertThat(redis.hasKey("auth:session:" + id)).isFalse();
  }

  @Test
  void separateProvidersDoNotShareAccounts() throws Exception {
    long kakaoId = result(login()).path("userId").asLong();
    createdUsers.add(kakaoId);
    var google = login("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}");
    assertThat(google.statusCode()).isEqualTo(200);
    long googleId = result(google).path("userId").asLong();
    createdUsers.add(googleId);
    assertThat(googleId).isNotEqualTo(kakaoId);
  }

  @Test
  void invalidRequestFieldsAreRejectedBeforeProviderVerification() throws Exception {
    assertThat(login("{\"provider\":0,\"providerToken\":\"token\"}").statusCode()).isEqualTo(400);
    assertThat(login("{\"provider\":\"KAKAO\",\"providerToken\":123}").statusCode()).isEqualTo(400);
    assertThat(login("{\"provider\":\"KAKAO\",\"providerToken\":true}").statusCode())
        .isEqualTo(400);
    assertThat(login("{\"provider\":\"APPLE\",\"providerToken\":\"token\"}").statusCode())
        .isEqualTo(400);
    assertThat(login("{\"provider\":\"KAKAO\",\"providerToken\":\"   \"}").statusCode())
        .isEqualTo(400);
    assertThat(login("{}").statusCode()).isEqualTo(400);
    verifyNoInteractions(verifier);
  }

  @Test
  void redisFailureDoesNotBypassAuthentication() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    doThrow(new DataAccessResourceFailureException("simulated Redis failure"))
        .when(store)
        .isCurrentSession(eq(id), anyString());
    assertThat(protectedStatus(signedIn.path("accessToken").asText())).isEqualTo(500);
  }

  @Test
  void lostRedisSessionRequiresLoginAgain() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    redis.delete("auth:session:" + id);
    assertThat(protectedStatus(signedIn.path("accessToken").asText())).isEqualTo(401);
  }

  HttpResponse<String> reissue(String refresh, String authorization) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/reissue"))
            .header("Content-Type", "application/json");
    if (authorization != null) builder.header("Authorization", authorization);
    String body = mapper.writeValueAsString(Map.of("refreshToken", refresh));
    return client.send(
        builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void refreshRotatesTokensAndRenewsFourteenDayExpiryWithoutAccessHeader() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    redis.expire("auth:session:" + id, Duration.ofSeconds(60));
    var response = reissue(original.path("refreshToken").asText(), null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH200_2");
    var next = result(response);
    assertThat(next.path("userId").asLong()).isEqualTo(id);
    assertThat(next.path("tokenType").asText()).isEqualTo("Bearer");
    assertThat(next.path("accessTokenExpiresIn").asLong()).isEqualTo(1800);
    assertThat(next.path("refreshTokenExpiresIn").asLong()).isEqualTo(1209600);
    assertThat(next.has("isNewUser")).isFalse();
    assertThat(next.path("refreshToken").asText())
        .isNotEqualTo(original.path("refreshToken").asText());
    assertThat(next.path("accessToken").asText())
        .isNotEqualTo(original.path("accessToken").asText());
    assertThat(redis.getExpire("auth:session:" + id)).isBetween(1209590L, 1209600L);
    var decoded = decoder.decode(next.path("refreshToken").asText());
    assertThat(decoded.getExpiresAt())
        .isBetween(Instant.now().plusSeconds(1209590), Instant.now().plusSeconds(1209601));
    assertThat(protectedStatus(next.path("accessToken").asText())).isEqualTo(404);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(reissue(next.path("refreshToken").asText(), null).statusCode()).isEqualTo(200);
  }

  @Test
  void reusedRefreshRevokesTheCurrentTokenFamily() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    String refresh = original.path("refreshToken").asText();
    var renewed = result(reissue(refresh, null));
    var replay = reissue(refresh, null);
    assertThat(replay.statusCode()).isEqualTo(401);
    assertThat(mapper.readTree(replay.body()).path("code").asText()).isEqualTo("AUTH401_3");
    assertThat(protectedStatus(renewed.path("accessToken").asText())).isEqualTo(401);
    assertThat(reissue(renewed.path("refreshToken").asText(), null).statusCode()).isEqualTo(401);
  }

  @Test
  void oldDeviceRefreshCannotRevokeNewLogin() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    String oldRefresh = original.path("refreshToken").asText();
    assertThat(reissue(oldRefresh, null).statusCode()).isEqualTo(200);
    var newDevice = result(login());
    assertThat(reissue(oldRefresh, null).statusCode()).isEqualTo(401);
    assertThat(protectedStatus(newDevice.path("accessToken").asText())).isEqualTo(404);
    assertThat(reissue(newDevice.path("refreshToken").asText(), null).statusCode()).isEqualTo(200);
  }

  @Test
  void concurrentRefreshOnlyConsumesTokenOnceAndDetectsReuse() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String refresh = signedIn.path("refreshToken").asText();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<HttpResponse<String>> call =
          () -> {
            start.await();
            return reissue(refresh, null);
          };
      var a = executor.submit(call);
      var b = executor.submit(call);
      start.countDown();
      var responses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
      assertThat(responses.stream().map(HttpResponse::statusCode).toList())
          .containsExactlyInAnyOrder(200, 401);
      assertThat(redis.hasKey("auth:session:" + id)).isFalse();
    }
  }

  @Test
  void expiredAccessHeaderDoesNotBlockValidRefresh() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    var refresh = decoder.decode(signedIn.path("refreshToken").asText());
    var expiredIssuer =
        new JwtTokenService(
            encoder, authProperties, Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));
    String expiredAccess =
        expiredIssuer.issue(
            users.findById(id).orElseThrow(), refresh.getClaimAsString("sid"), "access");
    assertThat(
            reissue(signedIn.path("refreshToken").asText(), "Bearer " + expiredAccess).statusCode())
        .isEqualTo(200);
  }

  @Test
  void wrongTokenKindsExpiredTokensAndLostSessionsAreRejected() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    assertThat(reissue(signedIn.path("accessToken").asText(), null).statusCode()).isEqualTo(401);
    assertThat(reissue("broken.jwt.token", null).statusCode()).isEqualTo(401);
    var refresh = decoder.decode(signedIn.path("refreshToken").asText());
    var expiredIssuer =
        new JwtTokenService(
            encoder,
            authProperties,
            Clock.fixed(Instant.now().minusSeconds(15 * 86400), ZoneOffset.UTC));
    String expiredRefresh =
        expiredIssuer.issue(
            users.findById(id).orElseThrow(), refresh.getClaimAsString("sid"), "refresh");
    assertThat(reissue(expiredRefresh, null).statusCode()).isEqualTo(401);
    assertThat(protectedStatus(signedIn.path("accessToken").asText())).isEqualTo(404);
    redis.delete("auth:session:" + id);
    assertThat(reissue(signedIn.path("refreshToken").asText(), null).statusCode()).isEqualTo(401);
  }

  @Test
  void suspendedAndWithdrawnMembersCannotRefresh() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String refresh = signedIn.path("refreshToken").asText();
    jdbc.update("UPDATE users SET account_status='SUSPENDED' WHERE user_id=?", id);
    assertThat(reissue(refresh, null).statusCode()).isEqualTo(403);
    jdbc.update(
        "UPDATE users SET account_status='WITHDRAWN',withdrawn_at=now(),restorable_until=now()+interval '720 hours',status_before_withdrawal='ACTIVE' WHERE user_id=?",
        id);
    var response = reissue(refresh, null);
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_3");
  }

  @Test
  void refreshRedisFailureIsServerErrorAndNeverIssuesTokens() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    doThrow(new DataAccessResourceFailureException("simulated Redis failure"))
        .when(store)
        .rotateRefreshToken(eq(id), anyString(), anyString(), anyString(), any(), any());
    var response = reissue(signedIn.path("refreshToken").asText(), null);
    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(mapper.readTree(response.body()).path("result").isNull()).isTrue();
  }

  @Test
  void refreshRequestRequiresStringToken() throws Exception {
    for (String body :
        List.of(
            "{}",
            "{\"refreshToken\":123}",
            "{\"refreshToken\":true}",
            "{\"refreshToken\":\" \"}",
            "{\"refreshToken\":\"has space\"}")) {
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/api/v1/auth/reissue"))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(400);
    }
  }

  @Test
  void healthIsPublicButOtherActuatorPathsRemainProtected() throws Exception {
    for (String path : List.of("/actuator/health", "/actuator/env", "/actuator/configprops")) {
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      if (path.equals("/actuator/health")) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("status").asText()).isEqualTo("UP");
        assertThat(mapper.readTree(response.body()).has("components")).isFalse();
      } else {
        assertThat(response.statusCode()).isEqualTo(401);
      }
    }
  }

  HttpResponse<String> logout(String token, String authorization) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/logout"))
            .header("Content-Type", "application/json");
    if (authorization != null) builder.header("Authorization", authorization);
    return client.send(
        builder
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(Map.of("refreshToken", token))))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void logoutImmediatelyRevokesBothTokensAndIsIdempotent() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    String refresh = original.path("refreshToken").asText();
    var first = logout(refresh, null);
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(first.body()).path("code").asText()).isEqualTo("AUTH200_3");
    assertThat(result(first).path("loggedOut").asBoolean()).isTrue();
    assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(protectedStatus(original.path("accessToken").asText())).isEqualTo(401);
    assertThat(reissue(refresh, null).statusCode()).isEqualTo(401);
    assertThat(logout(refresh, null).statusCode()).isEqualTo(200);
  }

  @Test
  void oldDeviceLogoutDoesNotRemoveNewLoginSession() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    var current = result(login());
    assertThat(logout(original.path("refreshToken").asText(), null).statusCode()).isEqualTo(200);
    assertThat(logout(original.path("refreshToken").asText(), null).statusCode()).isEqualTo(200);
    assertThat(protectedStatus(current.path("accessToken").asText())).isEqualTo(404);
    assertThat(reissue(current.path("refreshToken").asText(), null).statusCode()).isEqualTo(200);
  }

  @Test
  void rotatedTokenCanLogoutCurrentFamily() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    var renewed = result(reissue(original.path("refreshToken").asText(), null));
    assertThat(logout(original.path("refreshToken").asText(), null).statusCode()).isEqualTo(200);
    assertThat(protectedStatus(renewed.path("accessToken").asText())).isEqualTo(401);
    assertThat(logout(renewed.path("refreshToken").asText(), null).statusCode()).isEqualTo(200);
  }

  @Test
  void expiredRefreshIsAllowedOnlyWhenIssuanceIsProven() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    String sid = decoder.decode(original.path("refreshToken").asText()).getClaimAsString("sid");
    var pastIssuer =
        new JwtTokenService(
            encoder,
            authProperties,
            Clock.fixed(Instant.now().minusSeconds(15 * 86400), ZoneOffset.UTC));
    String expired = pastIssuer.issue(users.findById(id).orElseThrow(), sid, "refresh");
    assertThat(logout(expired, null).statusCode()).isEqualTo(401);
    assertThat(protectedStatus(original.path("accessToken").asText())).isEqualTo(404);
    redis
        .opsForHash()
        .put("auth:refresh-issued:" + id + ":" + sid, RedisAuthStore.hash(expired), "1");
    assertThat(logout(expired, null).statusCode()).isEqualTo(200);
    assertThat(logout(expired, null).statusCode()).isEqualTo(200);
    assertThat(protectedStatus(original.path("accessToken").asText())).isEqualTo(401);
  }

  @Test
  void inventedButValidlySignedRefreshCannotLogout() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    String sid = decoder.decode(original.path("refreshToken").asText()).getClaimAsString("sid");
    String unissued =
        new JwtTokenService(encoder, authProperties, Clock.systemUTC())
            .issue(users.findById(id).orElseThrow(), sid, "refresh");
    assertThat(logout(unissued, null).statusCode()).isEqualTo(401);
    assertThat(logout(original.path("accessToken").asText(), null).statusCode()).isEqualTo(401);
    assertThat(logout("broken.jwt.token", null).statusCode()).isEqualTo(401);
    assertThat(protectedStatus(original.path("accessToken").asText())).isEqualTo(404);
  }

  @Test
  void missingIssuanceHistoryDoesNotPretendLogoutSucceeded() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    String refresh = original.path("refreshToken").asText();
    String sid = decoder.decode(refresh).getClaimAsString("sid");
    redis.delete(List.of("auth:session:" + id, "auth:refresh-issued:" + id + ":" + sid));
    assertThat(logout(refresh, null).statusCode()).isEqualTo(401);
  }

  @Test
  void preExistingSessionWithoutIssuanceLedgerStillSupportsRepeatLogout() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    String refresh = original.path("refreshToken").asText();
    redis.delete(
        "auth:refresh-issued:" + id + ":" + decoder.decode(refresh).getClaimAsString("sid"));
    assertThat(logout(refresh, null).statusCode()).isEqualTo(200);
    assertThat(logout(refresh, null).statusCode()).isEqualTo(200);
  }

  @Test
  void concurrentLogoutIsIdempotent() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<HttpResponse<String>> call =
          () -> {
            start.await();
            return logout(original.path("refreshToken").asText(), null);
          };
      var a = executor.submit(call);
      var b = executor.submit(call);
      start.countDown();
      assertThat(a.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      assertThat(b.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }
    assertThat(redis.hasKey("auth:session:" + id)).isFalse();
  }

  @Test
  void logoutRacingRefreshAlwaysLeavesSessionRevoked() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    String refresh = original.path("refreshToken").asText();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var a =
          executor.submit(
              () -> {
                start.await();
                return logout(refresh, null);
              });
      var b =
          executor.submit(
              () -> {
                start.await();
                return reissue(refresh, null);
              });
      start.countDown();
      assertThat(a.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      assertThat(b.get(30, TimeUnit.SECONDS).statusCode()).isIn(200, 401);
    }
    assertThat(redis.hasKey("auth:session:" + id)).isFalse();
  }

  @Test
  void invalidAuthorizationHeaderDoesNotBlockLogout() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    assertThat(
            logout(original.path("refreshToken").asText(), "Bearer expired-or-invalid-access")
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  void logoutRedisFailureNeverReportsSuccess() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    doThrow(new DataAccessResourceFailureException("simulated Redis failure"))
        .when(store)
        .logoutSession(eq(id), anyString(), anyString());
    var response = logout(original.path("refreshToken").asText(), null);
    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(result(response).isNull()).isTrue();
  }

  @Test
  void logoutValidatesJsonTokenInput() throws Exception {
    for (String body :
        List.of(
            "{}",
            "{broken",
            "{\"refreshToken\":123}",
            "{\"refreshToken\":true}",
            "{\"refreshToken\":\" \"}",
            "{\"refreshToken\":\"has space\"}")) {
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/logout"))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(400);
    }
  }

  HttpResponse<String> restore(String token) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/restore"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(
                        Map.of("restoreToken", token, "agreeToRestore", true))))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  void withdraw(long id, String statusBeforeWithdrawal) {
    jdbc.update(
        "UPDATE users SET account_status='WITHDRAWN',withdrawn_at=now(),"
            + "restorable_until=now()+interval '720 hours',status_before_withdrawal=? WHERE user_id=?",
        statusBeforeWithdrawal,
        id);
  }

  @Test
  void restoreReturnsNewSessionAndRevertsAccountStatus() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    withdraw(id, "ACTIVE");
    String token = result(login()).path("restoreToken").asText();

    var restored = restore(token);
    assertThat(restored.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(restored.body()).path("code").asText()).isEqualTo("AUTH200_4");
    assertThat(restored.headers().firstValue("Cache-Control")).contains("no-store");
    var body = result(restored);
    assertThat(body.path("userId").asLong()).isEqualTo(id);
    assertThat(body.path("memberStatus").asText()).isEqualTo("ACTIVE");
    assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
    assertThat(body.path("isNewUser").asBoolean()).isFalse();
    assertThat(body.path("restored").asBoolean()).isTrue();

    var user = users.findById(id).orElseThrow();
    assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(user.getWithdrawnAt()).isNull();
    assertThat(user.getRestorableUntil()).isNull();
    assertThat(user.getStatusBeforeWithdrawal()).isNull();
    assertThat(protectedStatus(body.path("accessToken").asText())).isEqualTo(404);
  }

  @Test
  void restoreTokenCannotBeReusedAfterSuccess() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    withdraw(id, "PENDING_ONBOARDING");
    String token = result(login()).path("restoreToken").asText();

    assertThat(restore(token).statusCode()).isEqualTo(200);
    var replay = restore(token);
    assertThat(replay.statusCode()).isEqualTo(401);
    assertThat(mapper.readTree(replay.body()).path("code").asText()).isEqualTo("AUTH401_4");
  }

  @Test
  void concurrentRestoreOnlyConsumesTokenOnce() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    withdraw(id, "ACTIVE");
    String token = result(login()).path("restoreToken").asText();

    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<HttpResponse<String>> call =
          () -> {
            start.await();
            return restore(token);
          };
      var a = executor.submit(call);
      var b = executor.submit(call);
      start.countDown();
      var responses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
      assertThat(responses.stream().map(HttpResponse::statusCode).toList())
          .containsExactlyInAnyOrder(200, 401);

      var winner = responses.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow();
      var loser = responses.stream().filter(r -> r.statusCode() == 401).findFirst().orElseThrow();
      assertThat(mapper.readTree(loser.body()).path("code").asText()).isEqualTo("AUTH401_4");

      var body = result(winner);
      assertThat(body.path("userId").asLong()).isEqualTo(id);
      assertThat(body.path("memberStatus").asText()).isEqualTo("ACTIVE");
      assertThat(body.path("restored").asBoolean()).isTrue();
      assertThat(body.path("isNewUser").asBoolean()).isFalse();

      var user = users.findById(id).orElseThrow();
      assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
      assertThat(user.getWithdrawnAt()).isNull();
      assertThat(user.getRestorableUntil()).isNull();
      assertThat(user.getStatusBeforeWithdrawal()).isNull();

      String winningRefresh = body.path("refreshToken").asText();
      assertThat(redis.opsForHash().get("auth:session:" + id, "refreshHash"))
          .isEqualTo(RedisAuthStore.hash(winningRefresh));
      assertThat(protectedStatus(body.path("accessToken").asText())).isEqualTo(404);
      assertThat(redis.hasKey("auth:restore:" + id)).isFalse();
    }
  }

  @Test
  void restoreFailsIfAccountNoLongerWithdrawn() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    withdraw(id, "ACTIVE");
    String token = result(login()).path("restoreToken").asText();
    jdbc.update(
        "UPDATE users SET account_status='ACTIVE',withdrawn_at=null,restorable_until=null,"
            + "status_before_withdrawal=null WHERE user_id=?",
        id);

    var response = restore(token);
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH409_2");
  }

  @Test
  void restoreFailsWithGoneWhenWindowExpired() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    withdraw(id, "ACTIVE");
    String token = result(login()).path("restoreToken").asText();
    jdbc.update(
        "UPDATE users SET withdrawn_at=now()-interval '721 hours',"
            + "restorable_until=now()-interval '1 hour' WHERE user_id=?",
        id);

    var response = restore(token);
    assertThat(response.statusCode()).isEqualTo(410);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH410_1");
  }

  @Test
  void restoreRejectsInvalidOrMismatchedTokens() throws Exception {
    var original = result(login());
    long id = original.path("userId").asLong();
    createdUsers.add(id);
    withdraw(id, "ACTIVE");
    String token = result(login()).path("restoreToken").asText();

    assertThat(restore("not-a-restore-token").statusCode()).isEqualTo(401);
    assertThat(restore(id + ".wrong-suffix").statusCode()).isEqualTo(401);
    assertThat(restore("999999999999." + UUID.randomUUID() + UUID.randomUUID()).statusCode())
        .isEqualTo(401);
    assertThat(restore(token).statusCode()).isEqualTo(200);
  }

  @Test
  void restoreRequestRequiresStringTokenAndAgreement() throws Exception {
    for (String body :
        List.of(
            "{}",
            "{broken",
            "{\"restoreToken\":123,\"agreeToRestore\":true}",
            "{\"restoreToken\":true,\"agreeToRestore\":true}",
            "{\"restoreToken\":\" \",\"agreeToRestore\":true}",
            "{\"restoreToken\":\"has space\",\"agreeToRestore\":true}",
            "{\"restoreToken\":\"placeholder-token\"}",
            "{\"restoreToken\":\"placeholder-token\",\"agreeToRestore\":false}")) {
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + port + "/api/v1/auth/restore"))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(400);
    }
  }

  HttpResponse<String> withdrawMe(String accessToken) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me"))
            .header("Authorization", "Bearer " + accessToken)
            .DELETE()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void withdrawRevokesSessionAndRecordsRestorableState() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String access = signedIn.path("accessToken").asText();

    var response = withdrawMe(access);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("USER200_3");
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var body = result(response);
    assertThat(body.path("userId").asLong()).isEqualTo(id);
    assertThat(body.path("memberStatus").asText()).isEqualTo("WITHDRAWN");
    assertThat(body.path("withdrawnAt").asText()).isNotBlank();
    assertThat(body.path("restorableUntil").asText()).isNotBlank();

    var user = users.findById(id).orElseThrow();
    assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.WITHDRAWN);
    assertThat(user.getStatusBeforeWithdrawal()).isEqualTo(AccountStatus.PENDING_ONBOARDING);
    assertThat(user.getRestorableUntil()).isEqualTo(user.getWithdrawnAt().plusSeconds(720L * 3600));
    assertThat(redis.hasKey("auth:session:" + id)).isFalse();
    assertThat(protectedStatus(access)).isEqualTo(401);

    // Full journey: a withdrawn member logging in again gets the restore guide, and the
    // restore endpoint actually works against the row this test just transitioned.
    var recovery = login();
    assertThat(recovery.statusCode()).isEqualTo(409);
    assertThat(result(recovery).path("restorable").asBoolean()).isTrue();
    assertThat(restore(result(recovery).path("restoreToken").asText()).statusCode()).isEqualTo(200);
  }

  @Test
  void withdrawnAccountWithLingeringSessionIsRejectedWithForbidden() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String access = signedIn.path("accessToken").asText();
    // Simulate account state changing to WITHDRAWN without going through withdraw(), so the
    // Redis session (and thus this access token) is still current when AuthFilter checks it.
    withdraw(id, "ACTIVE");

    var response = withdrawMe(access);
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_3");
  }

  @Test
  void concurrentWithdrawOnlySucceedsOnce() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String access = signedIn.path("accessToken").asText();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<HttpResponse<String>> call =
          () -> {
            start.await();
            return withdrawMe(access);
          };
      var a = executor.submit(call);
      var b = executor.submit(call);
      start.countDown();
      var responses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
      assertThat(responses.stream().filter(r -> r.statusCode() == 200).count()).isEqualTo(1);
      // The loser can lose at three different points: AuthFilter's isCurrentSession check
      // (401, if the winner already revoked the session), AuthFilter's own status read seeing
      // WITHDRAWN after passing isCurrentSession but before reaching the controller (403), or
      // UserService's locked re-check after passing AuthFilter entirely (409).
      assertThat(responses.stream().map(HttpResponse::statusCode).toList())
          .allSatisfy(code -> assertThat(code).isIn(200, 401, 403, 409));

      assertThat(users.findById(id).orElseThrow().getAccountStatus())
          .isEqualTo(AccountStatus.WITHDRAWN);
      assertThat(redis.hasKey("auth:session:" + id)).isFalse();
    }
  }

  HttpResponse<String> getMyProfile(String accessToken) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me"))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void profileReturnsDefaultsForNewPendingOnboardingUser() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);

    var response = getMyProfile(signedIn.path("accessToken").asText());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("USER200_1");
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var body = result(response);
    assertThat(body.path("userId").asLong()).isEqualTo(id);
    assertThat(body.path("nickname").isNull()).isTrue();
    assertThat(body.path("profileImageUrl").isNull()).isTrue();
    assertThat(body.path("provider").asText()).isEqualTo("KAKAO");
    assertThat(body.path("ageRange").isNull()).isTrue();
    assertThat(body.path("memberStatus").asText()).isEqualTo("PENDING_ONBOARDING");
    assertThat(body.path("onboardingCompleted").asBoolean()).isFalse();
    assertThat(body.path("treeCount").asLong()).isEqualTo(0);
    assertThat(body.path("maxTreeCount").asInt()).isEqualTo(5);
    var settings = body.path("notificationSettings");
    assertThat(settings.path("activityReminderEnabled").asBoolean()).isTrue();
    assertThat(settings.path("treeGrowthEnabled").asBoolean()).isTrue();
    assertThat(settings.path("experienceOpenEnabled").asBoolean()).isTrue();
    assertThat(settings.path("announcementEnabled").asBoolean()).isTrue();
    var quietHours = settings.path("quietHours");
    assertThat(quietHours.path("enabled").asBoolean()).isFalse();
    assertThat(quietHours.path("start").asText()).isEqualTo("22:00");
    assertThat(quietHours.path("end").asText()).isEqualTo("08:00");
    assertThat(quietHours.path("timeZone").asText()).isEqualTo("Asia/Seoul");
    assertThat(body.path("createdAt").asText()).isNotBlank();
  }

  @Test
  void profileMarksOnboardingCompletedWhenActive() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);

    var body = result(getMyProfile(signedIn.path("accessToken").asText()));
    assertThat(body.path("memberStatus").asText()).isEqualTo("ACTIVE");
    assertThat(body.path("onboardingCompleted").asBoolean()).isTrue();
  }

  @Test
  void profileTreeCountIncludesOnlyActiveAndPausedTreesForTheAuthenticatedUser() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    seedUserHobbies(id, List.of("ACTIVE", "PAUSED", "ENDED"));

    // A different member's ACTIVE/PAUSED trees must not leak into this user's count.
    var otherSignedIn =
        result(login("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}"));
    long otherId = otherSignedIn.path("userId").asLong();
    createdUsers.add(otherId);
    seedUserHobbies(otherId, List.of("ACTIVE", "PAUSED"));

    var body = result(getMyProfile(signedIn.path("accessToken").asText()));
    assertThat(body.path("treeCount").asLong()).isEqualTo(2);
    // Seeded hobbies/user_hobbies rows are cleaned up by @AfterEach (for both users, via
    // createdUsers), so this still happens if the assertion above fails.
  }

  // UNIQUE(user_id, hobby_id) means each status needs its own hobby row.
  void seedUserHobbies(long userId, List<String> statuses) {
    for (String status : statuses) {
      long hobbyId =
          jdbc.queryForObject(
              "INSERT INTO hobbies (hobby_code, hobby_name) VALUES (?, ?) RETURNING hobby_id",
              Long.class,
              "profile-test-" + userId + "-" + status,
              "프로필 테스트 취미 " + status);
      jdbc.update(
          "INSERT INTO user_hobbies (user_id, hobby_id, tree_status) VALUES (?, ?, ?)",
          userId,
          hobbyId,
          status);
    }
  }

  // hobbies is a global catalog table with no cascade from the user; its FK also blocks
  // deleting a hobby row while user_hobbies still references it. Called from @AfterEach
  // before the user itself is deleted, so it runs even if the test failed earlier.
  void cleanupSeededHobbies(long userId) {
    jdbc.update("DELETE FROM user_hobbies WHERE user_id=?", userId);
    jdbc.update("DELETE FROM hobbies WHERE hobby_code LIKE ?", "profile-test-" + userId + "-%");
  }

  HttpResponse<String> updateProfile(String accessToken, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/users/me"))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void updateProfileAppliesOnlyProvidedFieldsAndTrimsNickname() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String access = signedIn.path("accessToken").asText();

    var first = updateProfile(access, "{\"nickname\":\"  즐거운수달  \"}");
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(first.body()).path("code").asText()).isEqualTo("USER200_2");
    assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
    var firstBody = result(first);
    assertThat(firstBody.path("userId").asLong()).isEqualTo(id);
    assertThat(firstBody.path("nickname").asText()).isEqualTo("즐거운수달");
    assertThat(firstBody.path("profileImageUrl").isNull()).isTrue();
    assertThat(firstBody.path("ageRange").isNull()).isTrue();
    String firstUpdatedAt = firstBody.path("updatedAt").asText();
    assertThat(firstUpdatedAt).isNotBlank();

    // Only ageRange is sent this time; the nickname set above must survive untouched.
    var second = updateProfile(access, "{\"ageRange\":\"TWENTIES\"}");
    assertThat(second.statusCode()).isEqualTo(200);
    var secondBody = result(second);
    assertThat(secondBody.path("nickname").asText()).isEqualTo("즐거운수달");
    assertThat(secondBody.path("ageRange").asText()).isEqualTo("TWENTIES");
    assertThat(secondBody.path("updatedAt").asText()).isNotEqualTo(firstUpdatedAt);

    var user = users.findById(id).orElseThrow();
    assertThat(user.getNickname()).isEqualTo("즐거운수달");
    assertThat(user.getAgeRange()).isEqualTo("TWENTIES");

    assertConcurrentProfileChangesArePreserved(id, access);
  }

  void assertConcurrentProfileChangesArePreserved(long id, String access) throws Exception {
    try (var executor = Executors.newSingleThreadExecutor();
        var connection = jdbc.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      try {
        int blockingPid;
        try (var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
          rows.next();
          blockingPid = rows.getInt(1);
        }
        // Keep a nickname change uncommitted while a separate HTTP request changes ageRange.
        try (var statement =
            connection.prepareStatement("UPDATE users SET nickname = ? WHERE user_id = ?")) {
          statement.setString(1, "ConcurrentNick");
          statement.setLong(2, id);
          statement.executeUpdate();
        }
        var pending = executor.submit(() -> updateProfile(access, "{\"ageRange\":\"THIRTIES\"}"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean blocked = false;
        while (System.nanoTime() < deadline && !pending.isDone()) {
          blocked =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity"
                          + " WHERE ? = ANY(pg_blocking_pids(pid)))",
                      Boolean.class,
                      blockingPid));
          if (blocked) break;
          Thread.sleep(25);
        }
        assertThat(blocked).as("profile request waits for the concurrent transaction").isTrue();
        connection.commit();
        var response = pending.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(result(response).path("nickname").asText()).isEqualTo("ConcurrentNick");
        var updated = users.findById(id).orElseThrow();
        assertThat(updated.getNickname()).isEqualTo("ConcurrentNick");
        assertThat(updated.getAgeRange()).isEqualTo("THIRTIES");
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  void updateProfileRejectsEmptyBody() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String access = signedIn.path("accessToken").asText();
    updateProfile(access, "{\"nickname\":\"즐거운수달\"}");

    var response = updateProfile(access, "{}");
    assertThat(response.statusCode()).isEqualTo(400);
    // The rejected request must not have cleared the nickname set above.
    assertThat(users.findById(id).orElseThrow().getNickname()).isEqualTo("즐거운수달");
  }

  @Test
  void updateProfileRejectsInvalidNicknameAndAgeRange() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    String access = signedIn.path("accessToken").asText();

    for (String body :
        List.of(
            "{\"nickname\":\"a\"}", // too short
            "{\"nickname\":\"가나다라마바사아자차카타파\"}", // 13자, too long
            "{\"nickname\":\"nick name\"}", // space not allowed
            "{\"nickname\":\"nick!\"}", // punctuation not allowed
            "{\"nickname\":123}",
            "{\"nickname\":true}",
            "{\"ageRange\":\"TEENAGER\"}", // not a valid enum value
            "{\"ageRange\":123}",
            "{broken")) {
      assertThat(updateProfile(access, body).statusCode()).isEqualTo(400);
    }

    // None of the rejected requests above actually changed anything.
    assertThat(users.findById(id).orElseThrow().getNickname()).isNull();
  }

  HttpResponse<String> getNotifications(String accessToken, String query) throws Exception {
    String url =
        "http://127.0.0.1:" + port + "/api/v1/notifications" + (query == null ? "" : "?" + query);
    return client.send(
        HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  void seedNotification(long userId, String type, String title, int minutesAgo, boolean read) {
    jdbc.update(
        "INSERT INTO user_notifications (user_id, notification_type, title, message, created_at, read_at) "
            + "VALUES (?, ?, ?, ?, now() - (? || ' minutes')::interval, CASE WHEN ? THEN now() ELSE NULL END)",
        userId,
        type,
        title,
        title + " 본문",
        minutesAgo,
        read);
  }

  long seedNotificationReturningId(long userId, String type, String title, int minutesAgo) {
    return jdbc.queryForObject(
        "INSERT INTO user_notifications (user_id, notification_type, title, message, created_at) "
            + "VALUES (?, ?, ?, ?, now() - (? || ' minutes')::interval) RETURNING notification_id",
        Long.class,
        userId,
        type,
        title,
        title + " 본문",
        minutesAgo);
  }

  HttpResponse<String> markNotificationRead(String accessToken, String notificationId)
      throws Exception {
    String url = "http://127.0.0.1:" + port + "/api/v1/notifications/" + notificationId + "/read";
    return client.send(
        HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .method("PATCH", HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void notificationListRejectsPendingOnboardingUser() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);

    var response = getNotifications(signedIn.path("accessToken").asText(), null);
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_2");
  }

  @Test
  void notificationListFiltersByReadStatusAndType() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();
    seedNotification(id, "TREE_GROWTH", "나무 성장", 10, false);
    seedNotification(id, "ANNOUNCEMENT", "공지", 5, true);

    // A second user's (GOOGLE fixture, so it's a distinct account) unread notification must
    // never leak into the first user's items or unreadCount below.
    var otherSignedIn =
        result(login("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}"));
    long otherId = otherSignedIn.path("userId").asLong();
    createdUsers.add(otherId);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", otherId);
    seedNotification(otherId, "TREE_GROWTH", "다른 사용자 알림", 1, false);

    var all = result(getNotifications(access, null));
    assertThat(all.path("items")).hasSize(2);
    assertThat(all.path("unreadCount").asLong()).isEqualTo(1);

    var unreadOnly = result(getNotifications(access, "isRead=false"));
    assertThat(unreadOnly.path("items")).hasSize(1);
    assertThat(unreadOnly.path("items").get(0).path("type").asText()).isEqualTo("TREE_GROWTH");
    assertThat(unreadOnly.path("items").get(0).path("isRead").asBoolean()).isFalse();
    assertThat(unreadOnly.path("items").get(0).path("target").isNull()).isTrue();

    var byType = result(getNotifications(access, "type=ANNOUNCEMENT"));
    assertThat(byType.path("items")).hasSize(1);
    assertThat(byType.path("items").get(0).path("title").asText()).isEqualTo("공지");
  }

  @Test
  void notificationListPaginatesWithCursor() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();
    for (int i = 0; i < 3; i++) seedNotification(id, "ANNOUNCEMENT", "공지" + i, i, false);

    var firstPage = result(getNotifications(access, "size=2"));
    assertThat(firstPage.path("items")).hasSize(2);
    assertThat(firstPage.path("hasNext").asBoolean()).isTrue();
    assertThat(firstPage.path("items").get(0).path("title").asText()).isEqualTo("공지0");
    assertThat(firstPage.path("items").get(1).path("title").asText()).isEqualTo("공지1");
    String cursor = firstPage.path("nextCursor").asText();

    var secondPage = result(getNotifications(access, "size=2&cursor=" + cursor));
    assertThat(secondPage.path("items")).hasSize(1);
    assertThat(secondPage.path("hasNext").asBoolean()).isFalse();
    assertThat(secondPage.path("items").get(0).path("title").asText()).isEqualTo("공지2");
  }

  @Test
  void notificationListCursorPreservesMicrosecondPrecision() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();
    // Same millisecond (.123), different microseconds: truncating the cursor to epoch millis
    // would make "older" indistinguishable from the "newer" boundary and drop it from page 2.
    jdbc.update(
        "INSERT INTO user_notifications (user_id, notification_type, title, message, created_at) "
            + "VALUES (?, 'ANNOUNCEMENT', 'newer', 'newer body', TIMESTAMPTZ '2026-01-01 00:00:00.123456+00')",
        id);
    jdbc.update(
        "INSERT INTO user_notifications (user_id, notification_type, title, message, created_at) "
            + "VALUES (?, 'ANNOUNCEMENT', 'older', 'older body', TIMESTAMPTZ '2026-01-01 00:00:00.123100+00')",
        id);

    var firstPage = result(getNotifications(access, "size=1"));
    assertThat(firstPage.path("items")).hasSize(1);
    assertThat(firstPage.path("items").get(0).path("title").asText()).isEqualTo("newer");
    assertThat(firstPage.path("hasNext").asBoolean()).isTrue();
    String cursor = firstPage.path("nextCursor").asText();

    var secondPage = result(getNotifications(access, "size=1&cursor=" + cursor));
    assertThat(secondPage.path("items")).hasSize(1);
    assertThat(secondPage.path("items").get(0).path("title").asText()).isEqualTo("older");
  }

  @Test
  void notificationListOrdersByIdDescendingWhenCreatedAtTies() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();

    // All rows share one createdAt: only "notificationId DESC" (and the cursor's
    // "createdAt = :beforeCreatedAt AND notificationId < :beforeId" tiebreak) can order or page
    // them deterministically. Losing that tiebreak could repeat or skip rows across a page
    // boundary without this test noticing, since every other test here uses distinct timestamps.
    for (int i = 0; i < 5; i++) {
      jdbc.update(
          "INSERT INTO user_notifications (user_id, notification_type, title, message, created_at) "
              + "VALUES (?, 'ANNOUNCEMENT', ?, ?, TIMESTAMPTZ '2026-01-01 00:00:00+00')",
          id,
          "tie" + i,
          "tie" + i + " 본문");
    }
    List<Long> expectedIds =
        jdbc.queryForList(
            "SELECT notification_id FROM user_notifications WHERE user_id = ? ORDER BY notification_id DESC",
            Long.class,
            id);

    List<Long> collectedIds = new ArrayList<>();
    String cursor = null;
    boolean hasNext = true;
    while (hasNext) {
      String query = "size=2" + (cursor == null ? "" : "&cursor=" + cursor);
      var page = result(getNotifications(access, query));
      for (var item : page.path("items")) collectedIds.add(item.path("notificationId").asLong());
      hasNext = page.path("hasNext").asBoolean();
      cursor = page.path("nextCursor").asText();
    }

    assertThat(collectedIds).isEqualTo(expectedIds);
  }

  @Test
  void notificationListRejectsInvalidQueryParams() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();

    assertThat(getNotifications(access, "type=NOT_A_TYPE").statusCode()).isEqualTo(400);
    assertThat(getNotifications(access, "size=0").statusCode()).isEqualTo(400);
    assertThat(getNotifications(access, "size=51").statusCode()).isEqualTo(400);
    assertThat(getNotifications(access, "cursor=YWJj").statusCode()).isEqualTo(400);
    assertThat(getNotifications(access, "isRead=notabool").statusCode()).isEqualTo(400);
  }

  @Test
  void notificationMarkReadSetsReadAtAndIsIdempotent() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);
    String access = signedIn.path("accessToken").asText();
    long notificationId = seedNotificationReturningId(id, "ANNOUNCEMENT", "공지", 5);

    var first = result(markNotificationRead(access, String.valueOf(notificationId)));
    assertThat(first.path("notificationId").asLong()).isEqualTo(notificationId);
    assertThat(first.path("isRead").asBoolean()).isTrue();
    assertThat(first.path("readAt").isNull()).isFalse();
    assertThat(first.path("unreadCount").asLong()).isEqualTo(0);
    String firstReadAt = first.path("readAt").asText();

    // A repeat request must keep the first readAt and not double-decrement unreadCount.
    var second = result(markNotificationRead(access, String.valueOf(notificationId)));
    assertThat(second.path("readAt").asText()).isEqualTo(firstReadAt);
    assertThat(second.path("unreadCount").asLong()).isEqualTo(0);
  }

  @Test
  void notificationMarkReadRejectsOtherUsersNotification() throws Exception {
    var ownerSignedIn = result(login());
    long ownerId = ownerSignedIn.path("userId").asLong();
    createdUsers.add(ownerId);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", ownerId);
    long notificationId = seedNotificationReturningId(ownerId, "ANNOUNCEMENT", "공지", 5);

    var otherSignedIn =
        result(login("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}"));
    long otherId = otherSignedIn.path("userId").asLong();
    createdUsers.add(otherId);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", otherId);

    var response =
        markNotificationRead(
            otherSignedIn.path("accessToken").asText(), String.valueOf(notificationId));
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(mapper.readTree(response.body()).path("code").asText())
        .isEqualTo("NOTIFICATION404_1");
  }

  @Test
  void notificationMarkReadRejectsPendingOnboardingUser() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);

    var response = markNotificationRead(signedIn.path("accessToken").asText(), "1");
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("AUTH403_2");
  }

  @Test
  void notificationMarkReadRejectsNonNumericId() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    jdbc.update("UPDATE users SET account_status='ACTIVE' WHERE user_id=?", id);

    assertThat(
            markNotificationRead(signedIn.path("accessToken").asText(), "not-a-number")
                .statusCode())
        .isEqualTo(400);
  }
}
