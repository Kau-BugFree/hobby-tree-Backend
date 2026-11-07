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

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, AuthResponseWriter.class, ClientIpResolver.class})
class CorsSecurityTests {
  private static final String PROFILE_PATH = "/api/v1/users/me";
  private static final String LOCAL_ORIGIN = "http://localhost:5173";

  @Autowired MockMvc mvc;

  @MockitoBean(name = "serviceJwtDecoder")
  JwtDecoder decoder;

  @MockitoBean RedisAuthStore store;
  @MockitoBean UserRepository users;
  @MockitoBean UserService userService;

  @Test
  void defaultProfileRejectsLocalhostPreflight() throws Exception {
    preflight(LOCAL_ORIGIN)
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
  }

  @Nested
  @TestPropertySource(properties = "CORS_ALLOWED_ORIGINS=https://web.example,https://admin.example")
  class ConfiguredOrigins {
    @Test
    void explicitOriginCanBeConfiguredWithoutTheLocalProfile() throws Exception {
      preflight("https://admin.example")
          .andExpect(status().isOk())
          .andExpect(
              header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://admin.example"));
      preflight(LOCAL_ORIGIN)
          .andExpect(status().isForbidden())
          .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
  }

  @Nested
  @ActiveProfiles("local")
  class LocalProfile {
    @Test
    void allowedPreflightSucceedsWithoutJwt() throws Exception {
      preflight(LOCAL_ORIGIN)
          .andExpect(status().isOk())
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, LOCAL_ORIGIN))
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "authorization"));
      verifyNoInteractions(decoder, store, users, userService);
    }

    @Test
    void unlistedOriginIsRejected() throws Exception {
      preflight("https://unlisted.example")
          .andExpect(status().isForbidden())
          .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void unauthenticatedGetStillReturnsTheJwtError() throws Exception {
      mvc.perform(get(PROFILE_PATH).header(HttpHeaders.ORIGIN, LOCAL_ORIGIN))
          .andExpect(status().isUnauthorized())
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, LOCAL_ORIGIN))
          .andExpect(jsonPath("$.code").value("AUTH401_2"));
    }

    @Test
    void optionsWithoutPreflightHeadersStillRequiresAuthentication() throws Exception {
      mvc.perform(options(PROFILE_PATH))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("AUTH401_2"));
    }
  }

  private ResultActions preflight(String origin) throws Exception {
    return mvc.perform(
        options(PROFILE_PATH)
            .header(HttpHeaders.ORIGIN, origin)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"));
  }
}
