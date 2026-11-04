package com.hobbytree.hobbytree.support;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.HobbytreeApplication;
import com.hobbytree.hobbytree.domain.auth.config.AuthProperties;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.auth.service.SocialTokenVerifier;
import com.hobbytree.hobbytree.domain.user.entity.AuthProvider;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Shared fixtures for the real, isolated-DB/Redis integration suites (AUTH_INTEGRATION_TEST=true,
// see scripts/ci/auth-test-entrypoint.sh). Split into one base class per domain test class
// (AuthIntegrationTests/UserIntegrationTests/NotificationIntegrationTests) instead of one
// ever-growing class, now that CI runs every *IntegrationTests class instead of only auth's.
// Domain-specific HTTP helpers (e.g. auth's restore()/withdraw()) stay in their own class even
// when a cross-domain test needs one from another domain, rather than living here: this class is
// only for infrastructure every domain needs (HTTP client, login, response parsing, cleanup).
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
@SpringBootTest(
    classes = HobbytreeApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {
  @LocalServerPort protected int port;
  @Autowired protected ObjectMapper mapper;
  @Autowired protected UserRepository users;
  @Autowired protected StringRedisTemplate redis;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected DataSource dataSource;
  @MockitoBean protected SocialTokenVerifier verifier;
  @MockitoSpyBean protected RedisAuthStore store;

  @Autowired
  @Qualifier("serviceJwtDecoder")
  protected JwtDecoder decoder;

  @Autowired protected JwtEncoder encoder;
  @Autowired protected AuthProperties authProperties;
  protected final Set<Long> createdUsers = ConcurrentHashMap.newKeySet();
  protected String socialId;
  protected final HttpClient client = HttpClient.newHttpClient();

  @BeforeEach
  protected void setup() {
    socialId = "integration-" + UUID.randomUUID();
    when(verifier.verify(any(), anyString())).thenReturn(socialId);
    clearRate();
  }

  private void clearRate() {
    List<String> prefixes =
        List.of(
            "auth:login-rate:", "auth:reissue-rate:", "auth:logout-rate:", "auth:restore-rate:");
    List<String> addresses = List.of("127.0.0.1", "0:0:0:0:0:0:0:1");
    redis.delete(
        prefixes.stream()
            .flatMap(
                prefix -> addresses.stream().map(address -> prefix + RedisAuthStore.hash(address)))
            .toList());
  }

  @AfterEach
  protected void cleanup() {
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

  protected HttpResponse<String> login(String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/social-login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  protected HttpResponse<String> login() throws Exception {
    return login("{\"provider\":\"KAKAO\",\"providerToken\":\"integration-test-token\"}");
  }

  protected JsonNode result(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("result");
  }

  protected int protectedStatus(String token) throws Exception {
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

  // hobbies is a global catalog table with no cascade from the user; its FK also blocks
  // deleting a hobby row while user_hobbies still references it. Called from @AfterEach above
  // before the user itself is deleted, so it runs even if the test failed earlier. A no-op for
  // suites that never seed hobbies (only UserIntegrationTests does).
  protected void cleanupSeededHobbies(long userId) {
    jdbc.update("DELETE FROM user_hobbies WHERE user_id=?", userId);
    jdbc.update("DELETE FROM hobbies WHERE hobby_code LIKE ?", "profile-test-" + userId + "-%");
  }
}
