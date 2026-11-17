package com.hobbytree.hobbytree.domain.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

// Single source of truth for routes that SecurityConfig permits anonymously and that do not
// have their own rate-limit handling in AuthFilter (unlike the four POST /auth/* endpoints,
// which AuthFilter checks explicitly before reaching the generic path below). AuthFilter must
// skip token validation for every pattern here - see issue #189: before this list existed,
// AuthFilter validated the Authorization header on these routes too, so a stale/invalid bearer
// token attached by a client (e.g. Swagger UI's Authorize button) wrongly blocked requests that
// SecurityConfig itself never requires authentication for.
public final class PublicEndpoints {
  public static final List<PublicEndpoint> ENTRIES =
      List.of(
          new PublicEndpoint(HttpMethod.GET, "/actuator/health"),
          new PublicEndpoint(HttpMethod.GET, "/api/v1/onboarding/options"),
          new PublicEndpoint(null, "/swagger-ui/**"),
          new PublicEndpoint(null, "/swagger-ui.html"),
          new PublicEndpoint(null, "/v3/api-docs/**"),
          new PublicEndpoint(null, "/error"),
          new PublicEndpoint(HttpMethod.GET, "/"),
          new PublicEndpoint(HttpMethod.GET, "/index.html"),
          new PublicEndpoint(HttpMethod.GET, "/hobbytree-logo.png"));

  private static final AntPathMatcher MATCHER = new AntPathMatcher();

  private PublicEndpoints() {}

  public static boolean matches(HttpServletRequest request) {
    String path = request.getServletPath();
    for (PublicEndpoint endpoint : ENTRIES) {
      boolean methodMatches =
          endpoint.method() == null
              || endpoint.method().name().equalsIgnoreCase(request.getMethod());
      if (methodMatches && MATCHER.match(endpoint.pattern(), path)) return true;
    }
    return false;
  }
}
