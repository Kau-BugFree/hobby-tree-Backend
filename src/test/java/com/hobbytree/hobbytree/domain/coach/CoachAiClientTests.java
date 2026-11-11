package com.hobbytree.hobbytree.domain.coach;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.coach.client.*;
import com.hobbytree.hobbytree.domain.coach.code.CoachErrorCode;
import com.hobbytree.hobbytree.domain.coach.config.CoachAiProperties;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CoachAiClientTests {
  private HttpServer server;
  private java.util.concurrent.ExecutorService executor;
  private final ObjectMapper mapper = new ObjectMapper();
  private final AtomicReference<String> received = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> token = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private int status = 200;
  private String response;
  private CountDownLatch bodyGate;
  private final CoachAiRequest request =
      new CoachAiRequest(42L, "요리 추천", "coach-turn-7", "cooking");

  @BeforeEach
  void start() throws Exception {
    response =
        mapper.writeValueAsString(
            new CoachAiResponse(
                42L, "자료 안내", "coach-v3.0.0", "browse", List.of(), List.of(), false));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/backend/coach/chat",
        exchange -> {
          calls.incrementAndGet();
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          token.set(exchange.getRequestHeaders().getFirst("X-Coach-Service-Token"));
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try {
            if (bodyGate != null) bodyGate.await(3, TimeUnit.SECONDS);
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
    if (bodyGate != null) bodyGate.countDown();
    server.stop(0);
    executor.shutdownNow();
  }

  private CoachAiClient client(Duration timeout) {
    return new CoachAiClient(
        new CoachAiProperties(
            true,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "isolated-test-token",
            Duration.ofSeconds(1),
            timeout),
        mapper);
  }

  private void error(CoachErrorCode code) {
    assertThatThrownBy(() -> client(Duration.ofSeconds(2)).chat(request))
        .isInstanceOfSatisfying(
            ProjectException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
  }

  @Test
  void sendsOnlyServiceTokenAndExactBridgeContract() {
    assertThat(client(Duration.ofSeconds(2)).chat(request).answer()).isEqualTo("자료 안내");
    assertThat(mapper.readValue(received.get(), CoachAiRequest.class)).isEqualTo(request);
    assertThat(token.get()).isEqualTo("isolated-test-token");
    assertThat(authorization.get()).isNull();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void rejectsUpstreamAuthenticationValidationAndServerFailuresWithoutLeakingBody() {
    for (int value : List.of(401, 403, 422, 500, 502)) {
      status = value;
      response = "private upstream diagnostic";
      error(CoachErrorCode.AI_BAD_RESPONSE);
    }
  }

  @Test
  void mapsUnavailableAndRateLimit() {
    for (int value : List.of(429, 503)) {
      status = value;
      error(CoachErrorCode.AI_UNAVAILABLE);
    }
  }

  @Test
  void doesNotFollowRedirectsOrForwardTokenElsewhere() {
    status = 302;
    error(CoachErrorCode.AI_BAD_RESPONSE);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void rejectsMalformedOversizedAndMismatchedReplies() {
    for (String bad :
        List.of(
            "null",
            "{}",
            "not-json",
            "x".repeat(270000),
            response.replace("42", "43"),
            response.replace("자료 안내", ""))) {
      response = bad;
      error(CoachErrorCode.AI_BAD_RESPONSE);
    }
  }

  @Test
  void timesOutWhileReadingBodyWithoutAutomaticRetry() {
    bodyGate = new CountDownLatch(1);
    assertThatThrownBy(() -> client(Duration.ofMillis(200)).chat(request))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(CoachErrorCode.AI_TIMEOUT));
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void disabledClientMakesNoRequestAndPropertiesNeverExposeToken() {
    var properties =
        new CoachAiProperties(
            false, null, "isolated-test-token", Duration.ofSeconds(1), Duration.ofSeconds(2));
    assertThat(properties.toString()).doesNotContain("isolated-test-token");
    assertThatThrownBy(() -> new CoachAiClient(properties, mapper).chat(request))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(CoachErrorCode.AI_UNAVAILABLE));
    assertThat(calls.get()).isZero();
  }

  @Test
  void validatesEnabledConfigurationBeforeSendingAnything() {
    for (String url :
        List.of(
            "file:///tmp/a",
            "http://user:pass@localhost",
            "http://localhost/path",
            "http://localhost?token=x")) {
      assertThatThrownBy(
              () ->
                  new CoachAiProperties(
                      true, URI.create(url), "token", Duration.ofSeconds(1), Duration.ofSeconds(2)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                new CoachAiProperties(
                    true,
                    URI.create("http://localhost"),
                    "",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CoachAiProperties(
                    false, null, "", Duration.ofSeconds(1), Duration.ofMinutes(5)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
