package com.hobbytree.hobbytree.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hobbytree.hobbytree.domain.coach.config.CoachAiProperties;
import com.hobbytree.hobbytree.domain.itemrecommendation.client.ItemRecommendationAiClient;
import com.hobbytree.hobbytree.domain.itemrecommendation.client.ItemRecommendationAiProperties;
import com.hobbytree.hobbytree.domain.itemrecommendation.client.ItemRecommendationAiRequest;
import com.hobbytree.hobbytree.domain.itemrecommendation.client.ItemRecommendationAiResponse;
import com.hobbytree.hobbytree.domain.recommendation.client.RecommendationAiProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Checks the packaged EC2 imports without starting any database, HTTP client or AWS client. */
class Ec2AiConfigurationTest {
  private static final String SYNTHETIC_TOKEN = "test_only_" + "x".repeat(55);
  private static final String SYNTHETIC_ITEM_TOKEN = "test_only_" + "y".repeat(55);

  @TempDir Path directory;

  /** Load the real profile file and only the three configuration records. */
  private ApplicationContextRunner runner() throws IOException {
    for (String name :
        new String[] {"DB_USERNAME", "DB_PASSWORD", "JWT_SECRET", "REDIS_PASSWORD"}) {
      Files.writeString(directory.resolve(name), "synthetic-test-only");
    }
    return new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues(
            "spring.config.location=classpath:application-ec2.yml",
            "spring.profiles.active=ec2",
            "APP_SECRETS_PATH=" + directory.toAbsolutePath().toString().replace('\\', '/') + "/");
  }

  @Test
  /** Existing four-file installations retain disabled clients and migration safety defaults. */
  void absentAiFileKeepsAllClientsDisabled() throws IOException {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(CoachAiProperties.class).enabled()).isFalse();
              assertThat(context.getBean(RecommendationAiProperties.class).enabled()).isFalse();
              assertThat(context.getBean(ItemRecommendationAiProperties.class).enabled()).isFalse();
              assertThat(context.getEnvironment().getProperty("spring.flyway.enabled"))
                  .isEqualTo("false");
              assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
                  .isEqualTo("validate");
            });
  }

  @Test
  /** The optional properties file binds actual kebab-case keys and does not expose its token. */
  void protectedAiFileBindsAllClients() throws IOException {
    writeAiFile(true);
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              CoachAiProperties coach = context.getBean(CoachAiProperties.class);
              RecommendationAiProperties recommendation =
                  context.getBean(RecommendationAiProperties.class);
              ItemRecommendationAiProperties itemRecommendation =
                  context.getBean(ItemRecommendationAiProperties.class);
              assertThat(coach.enabled()).isTrue();
              assertThat(coach.baseUrl().toString()).isEqualTo("http://10.0.0.12:8003");
              assertThat(coach.serviceToken()).isEqualTo(SYNTHETIC_TOKEN);
              assertThat(coach.toString()).doesNotContain(SYNTHETIC_TOKEN);
              assertThat(coach.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
              assertThat(coach.requestTimeout()).isEqualTo(Duration.ofSeconds(50));
              assertThat(recommendation.enabled()).isTrue();
              assertThat(recommendation.baseUrl().toString()).isEqualTo("http://10.0.0.12:8000");
              assertThat(recommendation.connectTimeout()).isEqualTo(Duration.ofMillis(1234));
              assertThat(recommendation.requestTimeout()).isEqualTo(Duration.ofSeconds(11));
              assertThat(itemRecommendation.enabled()).isTrue();
              assertThat(itemRecommendation.baseUrl().toString())
                  .isEqualTo("http://10.0.0.12:8001");
              assertThat(itemRecommendation.serviceToken()).isEqualTo(SYNTHETIC_ITEM_TOKEN);
              assertThat(itemRecommendation.toString()).doesNotContain(SYNTHETIC_ITEM_TOKEN);
              assertThat(itemRecommendation.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
              assertThat(itemRecommendation.requestTimeout()).isEqualTo(Duration.ofSeconds(9));
            });
  }

  @Test
  /**
   * Closes the loop between "the EC2 file binds" and "the client works": the bean Spring actually
   * builds from a file that also carries the coach/recommendation sections calls a real HTTP server
   * end to end, matching the AI team's documented contract. This is what plugging in a real
   * base-url/token once the AI server is up should look like - no other code change needed.
   */
  void beanBoundFromTheFullAiFileActuallyCallsAWorkingAiServer() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    var mapper = new ObjectMapper();
    var material = new ItemRecommendationAiRequest.Material(91001L, "계량스푼", "1개", true, null);
    var materialResult =
        new ItemRecommendationAiResponse.MaterialResult(
            material,
            "NOT_COLLECTED",
            "PENDING",
            List.of(),
            null,
            "PRIMARY",
            null,
            null,
            null,
            "구매 정보를 준비하고 있어요.",
            "SHOW_MATERIAL_GUIDE",
            0,
            null,
            0,
            null);
    String responseBody =
        mapper.writeValueAsString(
            new ItemRecommendationAiResponse(
                true,
                "ITEM_RECOMMENDATIONS_PROCESSED",
                "ok",
                new ItemRecommendationAiResponse.Result(
                    9001L,
                    3L,
                    0L,
                    null,
                    "AVAILABLE",
                    false,
                    "PRICE_ASC_NULLS_LAST",
                    List.of(materialResult),
                    "제휴 고지",
                    List.of())));
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/internal/v1/item-recommendations",
        exchange -> {
          calls.incrementAndGet();
          byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      writeAiFile(true, "http://127.0.0.1:" + server.getAddress().getPort());
      runner()
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                var properties = context.getBean(ItemRecommendationAiProperties.class);
                var client = new ItemRecommendationAiClient(properties, mapper);
                var request =
                    new ItemRecommendationAiRequest(
                        9001L,
                        3L,
                        "PUBLISHED",
                        List.of(
                            new ItemRecommendationAiRequest.Material(
                                91001L, "계량스푼", "1개", true, null)),
                        List.of(),
                        3);
                var result = client.recommend(request);
                assertThat(result.activityId()).isEqualTo(9001L);
              });
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  /** Operators can explicitly disable AI while preserving the protected file contract. */
  void explicitDisableIsHonored() throws IOException {
    writeAiFile(false);
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(CoachAiProperties.class).enabled()).isFalse();
              assertThat(context.getBean(RecommendationAiProperties.class).enabled()).isFalse();
              assertThat(context.getBean(ItemRecommendationAiProperties.class).enabled()).isFalse();
            });
  }

  /** Create only a synthetic fixture; production file permission checks run separately on Linux. */
  private void writeAiFile(boolean enabled) throws IOException {
    writeAiFile(enabled, "http://10.0.0.12:8001");
  }

  private void writeAiFile(boolean enabled, String itemRecommendationBaseUrl) throws IOException {
    Files.writeString(
        directory.resolve("ai.properties"),
        """
        recommendation.ai.enabled=%s
        recommendation.ai.base-url=http://10.0.0.12:8000
        recommendation.ai.connect-timeout=1234ms
        recommendation.ai.request-timeout=11s
        coach.ai.enabled=%s
        coach.ai.base-url=http://10.0.0.12:8003
        coach.ai.service-token=%s
        coach.ai.connect-timeout=3s
        coach.ai.request-timeout=50s
        item-recommendation.ai.enabled=%s
        item-recommendation.ai.base-url=%s
        item-recommendation.ai.service-token=%s
        item-recommendation.ai.connect-timeout=2s
        item-recommendation.ai.request-timeout=9s
        """
            .formatted(
                enabled,
                enabled,
                SYNTHETIC_TOKEN,
                enabled,
                itemRecommendationBaseUrl,
                SYNTHETIC_ITEM_TOKEN));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    CoachAiProperties.class,
    RecommendationAiProperties.class,
    ItemRecommendationAiProperties.class
  })
  static class PropertiesConfiguration {}
}
