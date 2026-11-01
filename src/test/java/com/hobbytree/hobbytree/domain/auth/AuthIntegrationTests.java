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
  void profileTreeCountIncludesOnlyActiveAndPausedTrees() throws Exception {
    var signedIn = result(login());
    long id = signedIn.path("userId").asLong();
    createdUsers.add(id);
    // UNIQUE(user_id, hobby_id) means each status needs its own hobby row.
    for (String status : List.of("ACTIVE", "PAUSED", "ENDED")) {
      long hobbyId =
          jdbc.queryForObject(
              "INSERT INTO hobbies (hobby_code, hobby_name) VALUES (?, ?) RETURNING hobby_id",
              Long.class,
              "profile-test-" + id + "-" + status,
              "프로필 테스트 취미 " + status);
      jdbc.update(
          "INSERT INTO user_hobbies (user_id, hobby_id, tree_status) VALUES (?, ?, ?)",
          id,
          hobbyId,
          status);
    }

    var body = result(getMyProfile(signedIn.path("accessToken").asText()));
    assertThat(body.path("treeCount").asLong()).isEqualTo(2);

    // hobbies is a global catalog table with no cascade from the user; its FK also blocks
    // deleting a hobby row while user_hobbies still references it, so both are cleaned up here
    // (before @AfterEach's user deletion) rather than relying on cascade.
    jdbc.update("DELETE FROM user_hobbies WHERE user_id=?", id);
    jdbc.update("DELETE FROM hobbies WHERE hobby_code LIKE ?", "profile-test-" + id + "-%");
  }
}
