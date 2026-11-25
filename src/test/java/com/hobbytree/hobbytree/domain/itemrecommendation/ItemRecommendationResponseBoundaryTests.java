package com.hobbytree.hobbytree.domain.itemrecommendation;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.itemrecommendation.client.*;
import com.hobbytree.hobbytree.domain.itemrecommendation.code.ItemRecommendationErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ItemRecommendationResponseBoundaryTests {
  private final ObjectMapper mapper = new ObjectMapper();
  private HttpServer server;
  private java.util.concurrent.ExecutorService executor;
  private final AtomicInteger calls = new AtomicInteger();
  private String response;
  private int status = 200;
  private boolean chunked;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/internal/v1/item-recommendations",
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
          try {
            for (int offset = 0; offset < bytes.length; offset += 997) {
              exchange.getResponseBody().write(bytes, offset, Math.min(997, bytes.length - offset));
              if (chunked) exchange.getResponseBody().flush();
            }
          } catch (IOException cancelled) {
            // Oversize reception is intentionally cancelled by the client.
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  private ItemRecommendationAiClient client() {
    return new ItemRecommendationAiClient(
        new ItemRecommendationAiProperties(
            true,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "isolated-test-token",
            Duration.ofSeconds(1),
            Duration.ofSeconds(5)),
        mapper);
  }

  static Stream<Arguments> acceptedBoundaries() {
    return Stream.of(
        Arguments.of(262143, false),
        Arguments.of(262144, false),
        Arguments.of(262143, true),
        Arguments.of(262144, true));
  }

  @ParameterizedTest
  @MethodSource("acceptedBoundaries")
  void preservesTwentyByFiveProductsAtUtf8Boundary(int bytes, boolean streaming) {
    chunked = streaming;
    response = ItemRecommendationBoundaryFixtures.sizedResponse(bytes);
    assertThat(response.length()).isLessThan(bytes);
    var result = client().recommend(ItemRecommendationBoundaryFixtures.request(20, 5));
    assertThat(result.materialResults()).hasSize(20);
    for (var material : result.materialResults()) {
      assertThat(material.products()).hasSize(5);
      assertThat(material.necessityReason()).isNull();
      assertThat(material.products().get(4).priceKrw()).isNull();
      assertThat(material.products().get(4).imageUrl()).isNull();
      assertThat(material.products().get(4).budgetComparison().status()).isEqualTo("PRICE_UNKNOWN");
    }
    var actual = mapper.readTree(mapper.writeValueAsBytes(result));
    assertThat(actual).isEqualTo(mapper.readTree(response).path("result"));
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsOneByteOverLimitWithoutRetry(boolean streaming) {
    chunked = streaming;
    response = ItemRecommendationBoundaryFixtures.sizedResponse(262145);
    badResponse(ItemRecommendationBoundaryFixtures.request(20, 5));
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3, 5})
  void rejectsMoreProductsThanRequested(int limit) {
    var request = ItemRecommendationBoundaryFixtures.request(1, limit);
    response =
        mapper.writeValueAsString(ItemRecommendationBoundaryFixtures.response(request, limit + 1));
    badResponse(request);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void oversizeErrorBodyIsAlsoBoundedWithoutRetry() {
    status = 503;
    chunked = true;
    response = "private diagnostic ".repeat(16000);
    badResponse(ItemRecommendationBoundaryFixtures.request(1, 1));
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void individuallyAllowedLongUrlsCanExceedTheTotalResponseBudget() {
    var request = ItemRecommendationBoundaryFixtures.request(20, 5);
    var root = ItemRecommendationBoundaryFixtures.response(request, 5);
    String prefix = "https://shop.example.invalid/p/";
    for (var row : root.path("result").path("materialResults")) {
      for (var product : row.path("products")) {
        ((ObjectNode) product).put("purchaseUrl", prefix + "a".repeat(4096 - prefix.length()));
      }
    }
    response = mapper.writeValueAsString(root);
    assertThat(response.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(262144);
    badResponse(request);
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "version",
        "reordered",
        "missing",
        "nullMaterial",
        "nullProduct",
        "blankOffer",
        "insecureUrl",
        "wrongType"
      })
  void malformedContractMapsToOpaqueBadResponse(String mutation) {
    var request = ItemRecommendationBoundaryFixtures.request(2, 1);
    var root = ItemRecommendationBoundaryFixtures.response(request, 1);
    var result = (ObjectNode) root.path("result");
    var rows = (ArrayNode) result.path("materialResults");
    var product = (ObjectNode) rows.get(0).path("products").get(0);
    switch (mutation) {
      case "version" -> result.put("activityContentVersion", 999);
      case "reordered" -> {
        var first = rows.remove(0);
        rows.add(first);
      }
      case "missing" -> rows.remove(0);
      case "nullMaterial" -> rows.set(0, mapper.nullNode());
      case "nullProduct" -> ((ArrayNode) rows.get(0).path("products")).set(0, mapper.nullNode());
      case "blankOffer" -> product.put("offerRef", " ");
      case "insecureUrl" -> product.put("purchaseUrl", "http://example.invalid/private");
      case "wrongType" -> product.putObject("priceKrw").put("private", "diagnostic");
      default -> throw new AssertionError(mutation);
    }
    response = mapper.writeValueAsString(root);
    badResponse(request);
    assertThat(calls.get()).isEqualTo(1);
  }

  private void badResponse(ItemRecommendationAiRequest request) {
    assertThatThrownBy(() -> client().recommend(request))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e -> {
              assertThat(e.getErrorCode()).isEqualTo(ItemRecommendationErrorCode.AI_BAD_RESPONSE);
              assertThat(e.getMessage()).doesNotContain("private diagnostic");
            });
  }
}
