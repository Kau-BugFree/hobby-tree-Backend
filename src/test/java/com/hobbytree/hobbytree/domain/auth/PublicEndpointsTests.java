package com.hobbytree.hobbytree.domain.auth;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.auth.security.PublicEndpoints;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

class PublicEndpointsTests {
  @ParameterizedTest
  @CsvSource({
    "GET,/actuator/health,true",
    "POST,/actuator/health,false",
    "GET,/api/v1/onboarding/options,true",
    "POST,/api/v1/onboarding/options,false",
    "GET,/swagger-ui/index.html,true",
    "GET,/swagger-ui.html,true",
    "GET,/v3/api-docs,true",
    "GET,/v3/api-docs/swagger-config,true",
    "POST,/error,true",
    "GET,/,true",
    "GET,/index.html,true",
    "GET,/hobbytree-logo.png,true",
    "GET,/api/v1/users/me,false",
    "POST,/api/v1/users/me,false",
    "GET,/api/v1/auth/social-login,false"
  })
  void matchesExactlyTheRoutesSecurityConfigPermitsAnonymously(
      String method, String path, boolean expected) {
    var request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    assertThat(PublicEndpoints.matches(request)).isEqualTo(expected);
  }
}
