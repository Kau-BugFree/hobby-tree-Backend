package com.hobbytree.hobbytree.domain.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.auth.security.AuthFilter;
import com.hobbytree.hobbytree.domain.auth.security.AuthResponseWriter;
import com.hobbytree.hobbytree.domain.auth.security.ClientIpResolver;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

// Issue #189: a stale/invalid bearer token must not block a route SecurityConfig permits
// anonymously - AuthFilter used to validate the header before reaching that check.
class AuthFilterTests {
  JwtDecoder decoder = mock(JwtDecoder.class);
  RedisAuthStore store = mock(RedisAuthStore.class);
  UserRepository users = mock(UserRepository.class);
  AuthResponseWriter writer = mock(AuthResponseWriter.class);
  ClientIpResolver clientIps = mock(ClientIpResolver.class);
  AuthFilter filter = new AuthFilter(decoder, store, users, writer, clientIps);
  FilterChain chain = mock(FilterChain.class);

  @ParameterizedTest
  @CsvSource({
    "GET,/actuator/health",
    "GET,/api/v1/onboarding/options",
    "GET,/swagger-ui/index.html",
    "GET,/v3/api-docs",
    "GET,/error",
    "GET,/",
    "GET,/index.html",
    "GET,/hobbytree-logo.png"
  })
  void invalidBearerTokenDoesNotBlockAPublicRoute(String method, String path) throws Exception {
    when(decoder.decode(anyString())).thenThrow(new JwtException("invalid"));
    var request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    request.addHeader("Authorization", "Bearer stale-or-invalid-token");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verifyNoInteractions(writer);
  }

  @Test
  void invalidBearerTokenStillBlocksAProtectedRoute() throws Exception {
    when(decoder.decode(anyString())).thenThrow(new JwtException("invalid"));
    var request = new MockHttpServletRequest("GET", "/api/v1/users/me");
    request.setServletPath("/api/v1/users/me");
    request.addHeader("Authorization", "Bearer stale-or-invalid-token");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    verifyNoInteractions(chain);
    verify(writer).write(eq(response), any(), any(), any());
  }

  @Test
  void publicRouteWithoutAnyAuthorizationHeaderAlsoPassesThrough() throws Exception {
    var request = new MockHttpServletRequest("GET", "/actuator/health");
    request.setServletPath("/actuator/health");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verifyNoInteractions(decoder, writer);
  }
}
