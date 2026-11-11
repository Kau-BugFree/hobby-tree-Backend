package com.hobbytree.hobbytree.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hobbytree.hobbytree.domain.coach.config.CoachAiProperties;
import com.hobbytree.hobbytree.domain.recommendation.client.RecommendationAiProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Checks the packaged EC2 imports without starting any database, HTTP client or AWS client. */
class Ec2AiConfigurationTest {
  private static final String SYNTHETIC_TOKEN = "test_only_" + "x".repeat(55);

  @TempDir Path directory;

  /** Load the real profile file and only the two configuration records. */
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
  void absentAiFileKeepsBothClientsDisabled() throws IOException {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(CoachAiProperties.class).enabled()).isFalse();
              assertThat(context.getBean(RecommendationAiProperties.class).enabled()).isFalse();
              assertThat(context.getEnvironment().getProperty("spring.flyway.enabled"))
                  .isEqualTo("false");
              assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
                  .isEqualTo("validate");
            });
  }

  @Test
  /** The optional properties file binds actual kebab-case keys and does not expose its token. */
  void protectedAiFileBindsBothClients() throws IOException {
    writeAiFile(true);
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              CoachAiProperties coach = context.getBean(CoachAiProperties.class);
              RecommendationAiProperties recommendation =
                  context.getBean(RecommendationAiProperties.class);
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
            });
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
            });
  }

  /** Create only a synthetic fixture; production file permission checks run separately on Linux. */
  private void writeAiFile(boolean enabled) throws IOException {
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
        """
            .formatted(enabled, enabled, SYNTHETIC_TOKEN));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({CoachAiProperties.class, RecommendationAiProperties.class})
  static class PropertiesConfiguration {}
}
