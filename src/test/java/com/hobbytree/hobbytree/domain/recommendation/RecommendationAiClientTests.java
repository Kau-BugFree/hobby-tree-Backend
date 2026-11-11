package com.hobbytree.hobbytree.domain.recommendation;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.recommendation.client.*;
import com.hobbytree.hobbytree.domain.recommendation.code.RecommendationErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class RecommendationAiClientTests {
  private final ObjectMapper mapper = new ObjectMapper();
  private HttpServer server;
  private ExecutorService executor;
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> received = new AtomicReference<>();
  private int status = 200;
  private String body = "{\"status\":\"recommended\"}";
  private CountDownLatch gate;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/api/v1/recommendations",
        exchange -> {
          calls.incrementAndGet();
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try {
            if (gate != null) gate.await(3, TimeUnit.SECONDS);
            exchange.getResponseBody().write(bytes);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    if (gate != null) gate.countDown();
    server.stop(0);
    executor.shutdownNow();
  }

  private RecommendationAiClient client(Duration timeout) {
    return new RecommendationAiClient(
        new RecommendationAiProperties(
            true,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            Duration.ofSeconds(1),
            timeout),
        mapper);
  }

  private void error(RecommendationErrorCode code) {
    assertThatThrownBy(() -> client(Duration.ofSeconds(2)).recommend(mapper.createObjectNode()))
        .isInstanceOfSatisfying(
            ProjectException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
  }

  @Test
  void sendsOnlyQuestionnaireAndNoUserToken() {
    var request = mapper.createObjectNode().put("schema_version", "test");
    assertThat(client(Duration.ofSeconds(2)).recommend(request).path("status").asText())
        .isEqualTo("recommended");
    assertThat(mapper.readTree(received.get())).isEqualTo(request);
    assertThat(authorization.get()).isNull();
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {301, 302, 401, 403, 422, 500, 502})
  void rejectsErrorsAndRedirectsWithoutDisclosingBody(int code) {
    status = code;
    body = "upstream diagnostic";
    error(RecommendationErrorCode.AI_BAD_RESPONSE);
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 503})
  void mapsUnavailable(int code) {
    status = code;
    error(RecommendationErrorCode.AI_UNAVAILABLE);
  }

  @Test
  void rejectsInvalidAndOversizedBodies() {
    body = "not-json";
    error(RecommendationErrorCode.AI_BAD_RESPONSE);
    body = "x".repeat(270000);
    error(RecommendationErrorCode.AI_BAD_RESPONSE);
  }

  @Test
  void boundsTimeEvenAfterResponseHeadersArrive() {
    gate = new CountDownLatch(1);
    assertThatThrownBy(() -> client(Duration.ofMillis(200)).recommend(mapper.createObjectNode()))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(RecommendationErrorCode.AI_TIMEOUT));
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void disabledMakesNoNetworkCall() {
    var disabled =
        new RecommendationAiClient(
            new RecommendationAiProperties(
                false, null, Duration.ofSeconds(1), Duration.ofSeconds(2)),
            mapper);
    assertThatThrownBy(() -> disabled.recommend(mapper.createObjectNode()))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(RecommendationErrorCode.AI_UNAVAILABLE));
    assertThat(calls.get()).isZero();
  }
}
