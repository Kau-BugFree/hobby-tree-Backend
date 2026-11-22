package com.hobbytree.hobbytree.domain.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Separate test class (and so a separate, small-pool Spring context) so this doesn't shrink the
// DB pool for every other *IntegrationTests class. Reproduces issue #185's actual failure mode
// directly: with the DB pool exactly as big as the number of concurrent logins below, code that
// still held a connection while waiting on a slow Redis call would leave zero connections free
// for the unrelated request and starve it. Pool size 1 is not used here even though each login
// only needs one connection at a time: the app's own startup (Flyway's own connection racing
// JPA bootstrap) needs a second connection to avoid deadlocking before any test runs.
//
// open-in-view is forced off to match the ec2 profile (application-ec2.yml): the default
// profile's open-in-view=true keeps a connection checked out for the whole HTTP request
// regardless of transaction boundaries, which would mask exactly the bug this test checks for.
@EnabledIfEnvironmentVariable(named = "AUTH_INTEGRATION_TEST", matches = "true")
class AuthRedisBlockingIntegrationTests extends IntegrationTestSupport {
  private static final int BLOCKED_LOGINS = 2;

  @DynamicPropertySource
  static void twoDbConnectionsAndNoOpenInView(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.hikari.maximum-pool-size", () -> String.valueOf(BLOCKED_LOGINS));
    registry.add("spring.datasource.hikari.minimum-idle", () -> String.valueOf(BLOCKED_LOGINS));
    registry.add("spring.jpa.open-in-view", () -> "false");
  }

  @Test
  void loginDoesNotHoldADbConnectionWhileWaitingOnRedis() throws Exception {
    var ready = new CountDownLatch(BLOCKED_LOGINS);
    var release = new CountDownLatch(1);
    // Only the two concurrent logins below must block here; the unrelated verification request
    // sent once both have - matches any arguments, so without this guard it would also match
    // (and deadlock waiting on its own release).
    var blocked = new AtomicInteger(0);
    doAnswer(
            invocation -> {
              if (blocked.incrementAndGet() <= BLOCKED_LOGINS) {
                ready.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).as("test released in time").isTrue();
              }
              return invocation.callRealMethod();
            })
        .when(store)
        .replaceSession(any(), any(), any(), anyLong());

    try (var executor = Executors.newFixedThreadPool(BLOCKED_LOGINS)) {
      Callable<HttpResponse<String>> call = this::login;
      var pending = List.of(executor.submit(call), executor.submit(call));
      assertThat(ready.await(5, TimeUnit.SECONDS))
          .as("every background login reached the blocked Redis call")
          .isTrue();

      // Both pool connections are now, at most, held by the two logins above up to the point
      // they committed - if either still held its connection here (the pre-fix behavior), the
      // pool would have zero free connections and this unrelated request would time out instead
      // of completing.
      var unrelated =
          login("{\"provider\":\"GOOGLE\",\"providerToken\":\"integration-google-token\"}");
      assertThat(unrelated.statusCode()).isEqualTo(200);
      createdUsers.add(result(unrelated).path("userId").asLong());

      release.countDown();
      for (var future : pending) {
        var response = future.get(5, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        createdUsers.add(result(response).path("userId").asLong());
      }
    }
  }
}
