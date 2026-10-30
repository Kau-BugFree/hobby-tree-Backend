package com.hobbytree.hobbytree.domain.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.hobbytree.hobbytree.domain.auth.controller.AuthController;
import com.hobbytree.hobbytree.domain.auth.dto.SocialLoginResponse;
import com.hobbytree.hobbytree.domain.auth.service.SocialLoginService;
import com.hobbytree.hobbytree.domain.auth.service.TokenReissueService;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.global.exception.GeneralExceptionAdvice;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

class SocialLoginInputTests {
  private final SocialLoginService login = mock(SocialLoginService.class);
  private final TokenReissueService reissue = mock(TokenReissueService.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        standaloneSetup(
                new AuthController(
                    login,
                    reissue,
                    mock(com.hobbytree.hobbytree.domain.auth.service.LogoutService.class)))
            .setControllerAdvice(new GeneralExceptionAdvice())
            .build();
  }

  static Stream<Arguments> invalidTokens() {
    return Stream.of("KAKAO", "GOOGLE")
        .flatMap(
            provider ->
                IntStream.of(0, 9, 10, 13, 31, 32, 127, 133, 0xD55C)
                    .mapToObj(
                        character ->
                            Arguments.of(provider, "prefix" + (char) character + "suffix")));
  }

  @ParameterizedTest(name = "invalid token case {index}")
  @MethodSource("invalidTokens")
  void rejectsControlsWhitespaceAndNonAsciiBeforeService(String provider, String token)
      throws Exception {
    mvc.perform(
            post("/api/v1/auth/social-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JsonMapper.builder()
                        .build()
                        .writeValueAsString(Map.of("provider", provider, "providerToken", token))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON400_1"))
        .andExpect(jsonPath("$.result.providerToken").exists());
    verifyNoInteractions(login, reissue);
  }

  @ParameterizedTest
  @ValueSource(strings = {"KAKAO", "GOOGLE"})
  void acceptsPrintableAsciiTokenForProviderValidation(String provider) throws Exception {
    when(login.login(any()))
        .thenReturn(
            new SocialLoginResponse(
                1L,
                "access",
                "refresh",
                "Bearer",
                1800,
                1209600,
                AccountStatus.PENDING_ONBOARDING,
                true));
    mvc.perform(
            post("/api/v1/auth/social-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JsonMapper.builder()
                        .build()
                        .writeValueAsString(
                            Map.of("provider", provider, "providerToken", "token_123-abc.def+/="))))
        .andExpect(status().isOk());
    verify(login).login(any());
    verifyNoInteractions(reissue);
  }
}
