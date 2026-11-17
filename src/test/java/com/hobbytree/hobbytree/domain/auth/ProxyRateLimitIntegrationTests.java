package com.hobbytree.hobbytree.domain.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.HobbytreeApplication;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.auth.service.SocialTokenVerifier;
import com.hobbytree.hobbytree.domain.user.entity.AuthProvider;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.net.URI;
import java.net.http.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

/** Real Tomcat + Redis requests; loopback stands in for the single trusted ALB hop. */
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@SpringBootTest(
    classes = HobbytreeApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"auth.proxy.trusted-cidrs=127.0.0.0/8", "server.forward-headers-strategy=none"})
class ProxyRateLimitIntegrationTests {
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired UserRepository users;
  @Autowired ObjectMapper mapper;
  @MockitoBean SocialTokenVerifier verifier;
  @MockitoSpyBean RedisAuthStore store;
  final HttpClient client = HttpClient.newHttpClient();
  final Set<Long> createdUsers = new HashSet<>();
  final List<String> addresses =
      List.of(
          "203.0.113.10",
          "203.0.113.11",
          "127.0.0.42",
          "127.0.0.1",
          "198.51.100.200",
          "2001:db8:0:0:0:0:0:a");

  void clearRates() {
    redis.delete(
        addresses.stream().map(ip -> "auth:login-rate:" + RedisAuthStore.hash(ip)).toList());
  }

  @BeforeEach
  void prepare() {
    clearRates();
    clearInvocations(store);
  }

  @AfterEach
  void cleanup() {
    clearRates();
    for (Long id : createdUsers) {
      redis.delete("auth:session:" + id);
      users.deleteById(id);
    }
    createdUsers.clear();
  }

  int login(String xff, String... extraHeaders) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/social-login"))
            .header("Content-Type", "application/json");
    if (xff != null) request.header("X-Forwarded-For", xff);
    for (int i = 0; i < extraHeaders.length; i += 2)
      request.header(extraHeaders[i], extraHeaders[i + 1]);
    return client
        .send(
            request.POST(HttpRequest.BodyPublishers.ofString("{broken")).build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  @Test
  void distinctUsersCanLoginThroughTheSameTrustedProxy() throws Exception {
    for (String clientIp : List.of("203.0.113.10", "203.0.113.11")) {
      String token = "proxy-sdk-test-" + UUID.randomUUID();
      when(verifier.verify(AuthProvider.KAKAO, token))
          .thenReturn("proxy-user-" + UUID.randomUUID());
      var request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + port + "/api/v1/auth/social-login"))
              .header("Content-Type", "application/json")
              .header("X-Forwarded-For", clientIp)
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      mapper.writeValueAsString(
                          java.util.Map.of("provider", "KAKAO", "providerToken", token))))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      long id = mapper.readTree(response.body()).path("result").path("userId").asLong();
      createdUsers.add(id);
      assertThat(redis.hasKey("auth:session:" + id)).isTrue();
      assertThat(redis.opsForValue().get("auth:login-rate:" + RedisAuthStore.hash(clientIp)))
          .isEqualTo("1");
    }
    assertThat(createdUsers).hasSize(2);
  }

  @Test
  void twoClientsBehindTheSameAlbDoNotShareTwentyRequestLimit() throws Exception {
    for (int i = 0; i < 20; i++) assertThat(login("203.0.113.10")).isEqualTo(400);
    assertThat(login("203.0.113.10")).isEqualTo(429);
    for (int i = 0; i < 20; i++) assertThat(login("203.0.113.11")).isEqualTo(400);
    assertThat(login("203.0.113.11")).isEqualTo(429);
  }

  @Test
  void changingForgedPrefixesCannotResetTheLimitOrSelectAVictim() throws Exception {
    for (int i = 0; i < 20; i++)
      assertThat(login("198.51.100." + i + ", 203.0.113.10")).isEqualTo(400);
    assertThat(login("198.51.100.200, 203.0.113.10")).isEqualTo(429);
    assertThat(redis.hasKey("auth:login-rate:" + RedisAuthStore.hash("198.51.100.200"))).isFalse();
    assertThat(login("203.0.113.10, 203.0.113.11")).isEqualTo(400);
  }

  @Test
  void privateClientMatchingProxyRangeIsNotSkipped() throws Exception {
    for (int i = 0; i < 20; i++)
      assertThat(login("198.51.100." + i + ", 127.0.0.42")).isEqualTo(400);
    assertThat(login("198.51.100.200, 127.0.0.42")).isEqualTo(429);
    assertThat(redis.opsForValue().get("auth:login-rate:" + RedisAuthStore.hash("127.0.0.42")))
        .isEqualTo("21");
  }

  @Test
  void equivalentIpv6AndChangingClientPortsShareOneBucket() throws Exception {
    for (int i = 0; i < 20; i++) assertThat(login("[2001:db8::a]:" + (40000 + i))).isEqualTo(400);
    assertThat(login("2001:0DB8:0:0:0:0:0:a")).isEqualTo(429);
  }

  @Test
  void malformedMissingAndDuplicateTrustedHeadersDoNotReachTheProviderOrStore() throws Exception {
    assertThat(login(null)).isEqualTo(400);
    for (String value : List.of("unknown", "localhost", "203.0.113.10,", "[::1]:0"))
      assertThat(login(value)).isEqualTo(400);
    assertThat(login("203.0.113.10", "X-Forwarded-For", "203.0.113.11")).isEqualTo(400);
    verify(store, never()).checkLoginRate(anyString());
    verifyNoInteractions(verifier);
  }

  @Test
  void otherForwardedHeadersCannotOverrideAlbsAppendedAddress() throws Exception {
    for (int i = 0; i < 20; i++)
      assertThat(
              login(
                  "203.0.113.10",
                  "Forwarded",
                  "for=198.51.100." + i,
                  "X-Real-IP",
                  "198.51.100." + i))
          .isEqualTo(400);
    assertThat(login("203.0.113.10", "Forwarded", "for=198.51.100.200")).isEqualTo(429);
  }

  @Test
  void ipv4MappedIpv6CannotCreateAnotherBucket() throws Exception {
    for (int i = 0; i < 20; i++) assertThat(login("203.0.113.10")).isEqualTo(400);
    assertThat(login("::ffff:203.0.113.10")).isEqualTo(429);
  }
}
