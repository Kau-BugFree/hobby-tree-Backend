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
            "{\"refreshToken\":\" \"}")) {
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
}
