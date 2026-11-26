package com.hobbytree.hobbytree.domain.coach;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.coach.client.CoachTranscriptionClient;
import com.hobbytree.hobbytree.domain.coach.code.CoachErrorCode;
import com.hobbytree.hobbytree.domain.coach.config.*;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

class CoachTranscriptionClientTests {
  HttpServer server;
  ExecutorService executor;
  int status = 200;
  String body = "{\"text\":\" 간장 두 큰술 \"}";
  AtomicInteger calls = new AtomicInteger();
  AtomicReference<byte[]> audio = new AtomicReference<>();
  AtomicReference<String> token = new AtomicReference<>(),
      type = new AtomicReference<>(),
      auth = new AtomicReference<>();
  CountDownLatch gate;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/backend/coach/transcribe",
        exchange -> {
          calls.incrementAndGet();
          audio.set(exchange.getRequestBody().readAllBytes());
          token.set(exchange.getRequestHeaders().getFirst("X-Coach-Service-Token"));
          auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          type.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          var bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Location", "/redirect-target");
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

  CoachTranscriptionClient client(boolean enabled, Duration timeout) {
    return new CoachTranscriptionClient(
        new CoachAiProperties(
            true,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "isolated-stt-token",
            Duration.ofSeconds(1),
            Duration.ofSeconds(120)),
        new CoachSttProperties(enabled, timeout),
        new ObjectMapper());
  }

  void error(CoachErrorCode code) {
    assertThatThrownBy(
            () -> client(true, Duration.ofSeconds(2)).transcribe(new byte[] {1, 2, 3}, "audio/mp4"))
        .isInstanceOfSatisfying(
            ProjectException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
  }

  @Test
  void sendsRawAudioWithServiceTokenAndKeepsLongTranscript() {
    String longText = "가".repeat(2500);
    body = "{\"text\":\"" + longText + "\"}";
    assertThat(
            client(true, Duration.ofSeconds(2))
                .transcribe(new byte[] {0, 1, 2}, "audio/webm")
                .text())
        .isEqualTo(longText);
    assertThat(audio.get()).containsExactly(0, 1, 2);
    assertThat(type.get()).isEqualTo("audio/webm");
    assertThat(token.get()).isEqualTo("isolated-stt-token");
    assertThat(auth.get()).isNull();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void mapsStatusesWithoutReturningDiagnosticsOrRetrying() {
    var statuses =
        Map.ofEntries(
            Map.entry(400, CoachErrorCode.STT_INVALID_AUDIO),
            Map.entry(413, CoachErrorCode.STT_TOO_LARGE),
            Map.entry(415, CoachErrorCode.STT_UNSUPPORTED_MEDIA),
            Map.entry(422, CoachErrorCode.STT_NO_SPEECH),
            Map.entry(401, CoachErrorCode.STT_UNAVAILABLE),
            Map.entry(403, CoachErrorCode.STT_UNAVAILABLE),
            Map.entry(429, CoachErrorCode.STT_UNAVAILABLE),
            Map.entry(503, CoachErrorCode.STT_UNAVAILABLE),
            Map.entry(504, CoachErrorCode.STT_TIMEOUT),
            Map.entry(500, CoachErrorCode.STT_BAD_RESPONSE),
            Map.entry(302, CoachErrorCode.STT_BAD_RESPONSE));
    body = "private diagnostic must not leak";
    statuses.forEach(
        (value, code) -> {
          status = value;
          error(code);
        });
    assertThat(calls.get()).isEqualTo(statuses.size());
  }

  @Test
  void rejectsInvalidAndOversizedResponses() {
    for (String invalid :
        List.of(
            "null",
            "{}",
            "[]",
            "not-json",
            "{\"text\":3}",
            "{\"text\":\"  \"}",
            "x".repeat(270000))) {
      body = invalid;
      error(CoachErrorCode.STT_BAD_RESPONSE);
    }
  }

  @Test
  void includesBodyReceptionInTimeoutWithoutRetry() {
    gate = new CountDownLatch(1);
    assertThatThrownBy(
            () -> client(true, Duration.ofMillis(250)).transcribe(new byte[] {1}, "audio/mp4"))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(CoachErrorCode.STT_TIMEOUT));
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void disabledSttNeverCallsAiAndHasIndependentBoundedTimeout() {
    assertThatThrownBy(
            () -> client(false, Duration.ofSeconds(45)).transcribe(new byte[] {1}, "audio/mp4"))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(CoachErrorCode.STT_UNAVAILABLE));
    assertThat(calls.get()).isZero();
    for (Duration invalid : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofSeconds(61))) {
      assertThatThrownBy(() -> new CoachSttProperties(false, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                new CoachTranscriptionClient(
                    new CoachAiProperties(
                        false, null, "", Duration.ofSeconds(2), Duration.ofSeconds(120)),
                    new CoachSttProperties(true, Duration.ofSeconds(45)),
                    new ObjectMapper()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
