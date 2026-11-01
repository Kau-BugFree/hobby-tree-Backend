package com.hobbytree.hobbytree.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

public record LogoutRequest(
    @NotBlank
        @Size(max = 16384)
        @Pattern(regexp = "^[\\x21-\\x7E]+$")
        @JsonDeserialize(using = SocialLoginRequest.TokenDeserializer.class)
        String refreshToken) {
  @Override
  public String toString() {
    return "LogoutRequest[refreshToken=[redacted]]";
  }
}
