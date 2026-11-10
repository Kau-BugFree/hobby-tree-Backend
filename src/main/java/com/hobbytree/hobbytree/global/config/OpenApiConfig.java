package com.hobbytree.hobbytree.global.config;

import org.springdoc.core.customizers.ServerBaseUrlCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  // ALB terminates TLS and forwards plain HTTP to the backend; server.forward-headers-strategy
  // is deliberately "none" (see application-ec2.yml) so Spring's own request handling - and
  // every security-sensitive use of client IP/scheme - never trusts X-Forwarded-*. This
  // customizer is the one narrow exception: it only affects the cosmetic server URL springdoc
  // shows in Swagger UI/the OpenAPI doc, not request handling, so reading the header here can't
  // reintroduce the spoofing risk forward-headers-strategy was turned off to avoid. Without it,
  // Swagger UI's "Execute" sends a plain-http request from the https page, which browsers block
  // as mixed content (surfaces as "Failed to fetch").
  @Bean
  public ServerBaseUrlCustomizer serverBaseUrlCustomizer() {
    return (serverBaseUrl, request) -> {
      String forwardedProto = request.getHeaders().getFirst("X-Forwarded-Proto");
      if ("https".equals(forwardedProto) && serverBaseUrl.startsWith("http://")) {
        return "https://" + serverBaseUrl.substring("http://".length());
      }
      return serverBaseUrl;
    };
  }
}
