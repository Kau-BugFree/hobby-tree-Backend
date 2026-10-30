package com.hobbytree.hobbytree.domain.auth.dto;

import com.hobbytree.hobbytree.domain.user.entity.AuthProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record SocialLoginRequest(
    @NotNull @JsonDeserialize(using = ProviderDeserializer.class) AuthProvider provider,
    @NotBlank @Size(max = 16384) @JsonDeserialize(using = TokenDeserializer.class)
        String providerToken) {

  // Reject Jackson's numeric-to-enum/string coercion for the login contract.
  public static class ProviderDeserializer extends ValueDeserializer<AuthProvider> {
    @Override
    public AuthProvider deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        return context.reportInputMismatch(AuthProvider.class, "provider must be a JSON string");
      }
      try {
        return AuthProvider.valueOf(parser.getString());
      } catch (IllegalArgumentException e) {
        return context.reportInputMismatch(AuthProvider.class, "provider must be KAKAO or GOOGLE");
      }
    }
  }

  public static class TokenDeserializer extends ValueDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        return context.reportInputMismatch(String.class, "providerToken must be a JSON string");
      }
      return parser.getString();
    }
  }

  @Override
  public String toString() {
    return "SocialLoginRequest[provider=" + provider + ", providerToken=[redacted]]";
  }
}
