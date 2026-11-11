package com.hobbytree.hobbytree.global.config;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.security.AuthFilter;
import com.hobbytree.hobbytree.domain.auth.security.AuthResponseWriter;
import com.hobbytree.hobbytree.domain.auth.security.ClientIpResolver;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.Assert;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

  @Bean
  UrlBasedCorsConfigurationSource corsConfigurationSource(
      @Value("${cors.allowed-origins:}") List<String> allowedOrigins,
      @Value("${cors.public-api-origin:}") String publicApiOrigin,
      @Value("${cors.expo-web.enabled:false}") boolean expoWebEnabled) {
    var origins = new ArrayList<>(allowedOrigins);
    if (!publicApiOrigin.isBlank() && !origins.contains(publicApiOrigin)) {
      origins.add(publicApiOrigin);
    }
    if (expoWebEnabled && !origins.contains("http://localhost:8081")) {
      origins.add("http://localhost:8081");
    }
    Assert.isTrue(
        origins.stream()
            .noneMatch(origin -> origin.contains("*") || origin.strip().equalsIgnoreCase("null")),
        "CORS must list explicit origins");
    var config = new CorsConfiguration();
    config.setAllowedOrigins(origins);
    config.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"));
    config.setAllowedHeaders(
        List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "Idempotency-Key"));
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/v1/**", config);
    return source;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      @Qualifier("serviceJwtDecoder") JwtDecoder decoder,
      RedisAuthStore store,
      UserRepository users,
      AuthResponseWriter writer,
      ClientIpResolver clientIps,
      UrlBasedCorsConfigurationSource corsConfigurationSource)
      throws Exception {

    http.cors(cors -> cors.configurationSource(corsConfigurationSource))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(org.springframework.http.HttpMethod.GET, "/actuator/health")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/api/v1/onboarding/options")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/auth/social-login",
                        "/api/v1/auth/reissue",
                        "/api/v1/auth/logout",
                        "/api/v1/auth/restore")
                    .permitAll()
                    .requestMatchers(
                        "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/error")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/",
                        "/index.html",
                        "/hobbytree-logo.png")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            writer.write(response, AuthErrorCode.ACCESS_TOKEN, null, null))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            writer.write(
                                response,
                                com.hobbytree
                                    .hobbytree
                                    .global
                                    .apiPayload
                                    .code
                                    .GeneralErrorCode
                                    .COMMON_FORBIDDEN,
                                null,
                                null)))
        .addFilterBefore(
            new AuthFilter(decoder, store, users, writer, clientIps),
            UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }
}
