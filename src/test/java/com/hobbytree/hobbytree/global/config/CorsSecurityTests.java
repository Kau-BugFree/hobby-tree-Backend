package com.hobbytree.hobbytree.global.config;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hobbytree.hobbytree.domain.auth.security.AuthResponseWriter;
import com.hobbytree.hobbytree.domain.auth.security.ClientIpResolver;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.experience.service.ExperienceReservationService;
import com.hobbytree.hobbytree.domain.onboarding.controller.OnboardingController;
import com.hobbytree.hobbytree.domain.onboarding.service.OnboardingService;
import com.hobbytree.hobbytree.domain.user.controller.UserController;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.domain.user.service.UserService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest({UserController.class, OnboardingController.class})
@Import({SecurityConfig.class, AuthResponseWriter.class, ClientIpResolver.class})
class CorsSecurityTests {
  private static final String EXPO_ORIGIN = "http://localhost:8081";
  private static final String LOGIN = "/api/v1/auth/social-login";
  private static final String PROFILE = "/api/v1/users/me";
  private static final String ONBOARDING = "/api/v1/onboarding/options";

  @Autowired MockMvc mvc;

  @MockitoBean(name = "serviceJwtDecoder")
  JwtDecoder decoder;

  @MockitoBean RedisAuthStore store;
  @MockitoBean UserRepository users;
  @MockitoBean UserService userService;
  @MockitoBean ExperienceReservationService experienceReservations;
  @MockitoBean OnboardingService onboarding;

  @Test
  void expoWebIsDisabledOutsideTheOptedInProfile() throws Exception {
    preflight(LOGIN, EXPO_ORIGIN, "POST", "content-type")
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
  }

  @Nested
  @ActiveProfiles("ec2")
  @TestPropertySource(
      properties = {
        "APP_SECRETS_PATH=src/test/resources/",
        "CORS_ALLOWED_ORIGINS=https://web.example"
      })
  class Ec2Profile {
    @Test
    void loginPreflightAndPublicOnboardingWorkFromExpoWeb() throws Exception {
      preflight(LOGIN, EXPO_ORIGIN, "POST", "content-type")
          .andExpect(status().isOk())
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, EXPO_ORIGIN))
          .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
      mvc.perform(get(ONBOARDING).header(HttpHeaders.ORIGIN, EXPO_ORIGIN))
          .andExpect(status().isOk())
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, EXPO_ORIGIN));
      verifyNoInteractions(decoder, store, users, userService);
    }

    @Test
    void recommendationAndCoachPreflightAllowTheRequiredIdempotencyHeader() throws Exception {
      for (String path : new String[] {"/api/v1/recommendations", "/api/v1/coach/chat"}) {
        preflight(path, EXPO_ORIGIN, "POST", "authorization, content-type, idempotency-key")
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, EXPO_ORIGIN))
            .andExpect(
                header()
                    .string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        "authorization, content-type, idempotency-key"));
      }
      verifyNoInteractions(decoder, store, users, userService);
    }

    @Test
    void preservesConfiguredHttpsOriginAndRejectsOtherOrigins() throws Exception {
      preflight(PROFILE, "https://web.example", "GET", "authorization")
          .andExpect(status().isOk())
          .andExpect(
              header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://web.example"));
      for (String origin :
          new String[] {
            "http://localhost:8082", "http://127.0.0.1:8081", "https://unlisted.example"
          }) {
        preflight(LOGIN, origin, "POST", "content-type")
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
      }
    }

    @Test
    void browserAndNativeRequestsStillRequireJwtForProtectedApis() throws Exception {
      mvc.perform(get(PROFILE).header(HttpHeaders.ORIGIN, EXPO_ORIGIN))
          .andExpect(status().isUnauthorized())
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, EXPO_ORIGIN))
          .andExpect(jsonPath("$.code").value("AUTH401_2"));
      mvc.perform(get(PROFILE))
          .andExpect(status().isUnauthorized())
          .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
      mvc.perform(options(PROFILE)).andExpect(status().isUnauthorized());
    }

    @Test
    void unrelatedRequestHeadersAreRejected() throws Exception {
      preflight(LOGIN, EXPO_ORIGIN, "POST", "x-unlisted-header")
          .andExpect(status().isForbidden())
          .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
  }

  private ResultActions preflight(String path, String origin, String method, String headers)
      throws Exception {
    return mvc.perform(
        options(path)
            .header(HttpHeaders.ORIGIN, origin)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, headers));
  }
}
