package com.hobbytree.hobbytree.global.config;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.security.AuthFilter;
import com.hobbytree.hobbytree.domain.auth.security.AuthResponseWriter;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      @Qualifier("serviceJwtDecoder") JwtDecoder decoder,
      RedisAuthStore store,
      UserRepository users,
      AuthResponseWriter writer)
      throws Exception {

    http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/v1/auth/social-login",
                        "/api/v1/auth/reissue")
                    .permitAll()
                    .requestMatchers(
                        "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/error")
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
            new AuthFilter(decoder, store, users, writer),
            UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }
}
