package com.hobbytree.hobbytree.domain.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

public record RestoreRequest(
    @NotBlank
        @Size(max = 16384)
        @Pattern(regexp = "^[\\x21-\\x7E]+$")
        @JsonDeserialize(using = SocialLoginRequest.TokenDeserializer.class)
        String restoreToken,
    @NotNull @AssertTrue Boolean agreeToRestore) {
  @Override
  public String toString() {
    return "RestoreRequest[restoreToken=[redacted], agreeToRestore=" + agreeToRestore + "]";
  }
}
