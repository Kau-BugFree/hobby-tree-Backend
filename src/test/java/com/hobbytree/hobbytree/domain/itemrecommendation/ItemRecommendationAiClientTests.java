package com.hobbytree.hobbytree.domain.itemrecommendation;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.itemrecommendation.client.*;
import com.hobbytree.hobbytree.domain.itemrecommendation.code.ItemRecommendationErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ItemRecommendationAiClientTests {
  private HttpServer server;
  private java.util.concurrent.ExecutorService executor;
  private final ObjectMapper mapper = new ObjectMapper();
  private final AtomicReference<String> received = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> token = new AtomicReference<>();
  private int status = 200;
  private String response;
  private CountDownLatch bodyGate;

  private final ItemRecommendationAiRequest.Material material =
      new ItemRecommendationAiRequest.Material(91001L, "계량스푼", "1개", true, null);
  private final ItemRecommendationAiRequest.Filters emptyFilters =
      new ItemRecommendationAiRequest.Filters(List.of(), null, null, List.of());
  private final ItemRecommendationAiRequest request =
      new ItemRecommendationAiRequest(
          9001L,
          3L,
          "PUBLISHED",
          List.of(material),
          List.of(new ItemRecommendationAiRequest.Selection(91001L, 10000L, 0, null, emptyFilters)),
          1);

  private String validResponse() {
    var product =
        new ItemRecommendationAiResponse.Product(
            "example_offer_001",
            "ADPICK",
            null,
            "예시 계량스푼",
            "5,900원",
            5900L,
            "KRW",
            null,
            "https://link.adpick.co.kr/example-only",
            "EXAMPLE_MERCHANT",
            "예시 판매처",
            Instant.parse("2026-11-20T03:30:00Z"),
            true,
            "UNKNOWN",
            new ItemRecommendationAiResponse.BudgetComparison(
                "WITHIN_BUDGET",
                10000L,
                -4100L,
                "설정한 예산보다 4,100원 낮아요.",
                "DISPLAYED_PRODUCT_PRICE"));
    var materialResult =
        new ItemRecommendationAiResponse.MaterialResult(
            material,
            "AVAILABLE",
            "SUCCESS",
            List.of(product),
            null,
            "PRIMARY",
            "example-catalog-revision-001",
            Instant.parse("2026-11-21T03:30:00Z"),
            Instant.parse("2026-11-20T03:30:00Z"),
            "낮은 가격순으로 보여드려요.",
            "NONE",
            3,
            1,
            0,
            Map.of());
    var result =
        new ItemRecommendationAiResponse.Result(
            9001L,
            3L,
            0L,
            Instant.parse("2026-11-20T09:00:00Z"),
            "AVAILABLE",
            false,
            "PRICE_ASC_NULLS_LAST",
            List.of(materialResult),
            "제휴 고지",
            List.of());
    return mapper.writeValueAsString(
        new ItemRecommendationAiResponse(true, "ITEM_RECOMMENDATIONS_PROCESSED", "ok", result));
  }

  @BeforeEach
  void start() throws Exception {
    response = validResponse();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/internal/v1/item-recommendations",
        exchange -> {
          calls.incrementAndGet();
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          token.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
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

  private ItemRecommendationAiClient client(Duration timeout) {
    return new ItemRecommendationAiClient(
        new ItemRecommendationAiProperties(
            true,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "isolated-test-token",
            Duration.ofSeconds(1),
            timeout),
        mapper);
  }

  private void error(ItemRecommendationErrorCode code) {
    assertThatThrownBy(() -> client(Duration.ofSeconds(2)).recommend(request))
        .isInstanceOfSatisfying(
            ProjectException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
  }

  @Test
  void sendsOnlyServiceTokenAndExactContractThenReturnsInnerResult() {
    var result = client(Duration.ofSeconds(2)).recommend(request);
    assertThat(result.activityId()).isEqualTo(9001L);
    assertThat(result.materialResults()).hasSize(1);
    assertThat(result.materialResults().get(0).products().get(0).priceKrw()).isEqualTo(5900L);
    assertThat(mapper.readValue(received.get(), ItemRecommendationAiRequest.class))
        .isEqualTo(request);
    assertThat(token.get()).isEqualTo("isolated-test-token");
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void rejectsUpstreamAuthenticationValidationAndServerFailuresWithoutLeakingBody() {
    for (int value : List.of(401, 413, 422, 500, 502)) {
      status = value;
      response = "private upstream diagnostic";
      error(ItemRecommendationErrorCode.AI_BAD_RESPONSE);
    }
  }

  @Test
  void mapsCatalogUnavailable() {
    status = 503;
    error(ItemRecommendationErrorCode.AI_UNAVAILABLE);
  }

  @Test
  void doesNotFollowRedirects() {
    status = 302;
    error(ItemRecommendationErrorCode.AI_BAD_RESPONSE);
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
            response.replace("9001", "9002"),
            response.replace("https://link.adpick.co.kr/example-only", "http://insecure"))) {
      response = bad;
      error(ItemRecommendationErrorCode.AI_BAD_RESPONSE);
    }
  }

  @Test
  void timesOutWhileReadingBodyWithoutAutomaticRetry() {
    bodyGate = new CountDownLatch(1);
    assertThatThrownBy(() -> client(Duration.ofMillis(200)).recommend(request))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ItemRecommendationErrorCode.AI_TIMEOUT));
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void disabledClientMakesNoRequestAndPropertiesNeverExposeToken() {
    var properties =
        new ItemRecommendationAiProperties(
            false, null, "isolated-test-token", Duration.ofSeconds(1), Duration.ofSeconds(2));
    assertThat(properties.toString()).doesNotContain("isolated-test-token");
    assertThatThrownBy(() -> new ItemRecommendationAiClient(properties, mapper).recommend(request))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e ->
                assertThat(e.getErrorCode()).isEqualTo(ItemRecommendationErrorCode.AI_UNAVAILABLE));
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
                  new ItemRecommendationAiProperties(
                      true, URI.create(url), "token", Duration.ofSeconds(1), Duration.ofSeconds(2)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                new ItemRecommendationAiProperties(
                    true,
                    URI.create("http://localhost"),
                    "",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ItemRecommendationAiProperties(
                    false, null, "", Duration.ofSeconds(1), Duration.ofSeconds(40)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
