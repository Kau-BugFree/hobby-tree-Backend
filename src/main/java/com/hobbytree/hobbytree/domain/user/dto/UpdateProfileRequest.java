package com.hobbytree.hobbytree.domain.user.dto;

import com.hobbytree.hobbytree.domain.auth.dto.SocialLoginRequest;
import com.hobbytree.hobbytree.domain.user.entity.AgeRange;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record UpdateProfileRequest(
    @Pattern(regexp = "^[가-힣A-Za-z0-9_]{2,12}$")
        @JsonDeserialize(using = SocialLoginRequest.TokenDeserializer.class)
        String nickname,
    @JsonDeserialize(using = UpdateProfileRequest.AgeRangeDeserializer.class) AgeRange ageRange,
    String profileImageKey) {

  // All fields are optional; omitting one leaves the current value unchanged. Trim before the
  // @Pattern check runs, matching API-06's "앞뒤 공백 제거 후" validation order.
  public UpdateProfileRequest(String nickname, AgeRange ageRange, String profileImageKey) {
    this.nickname = nickname == null ? null : nickname.trim();
    this.ageRange = ageRange;
    this.profileImageKey = profileImageKey;
  }

  // At least one field must be present; an empty body changes nothing and is rejected.
  @AssertTrue(message = "nickname 또는 ageRange 중 최소 하나는 포함해야 합니다")
  public boolean isAnyFieldProvided() {
    return nickname != null || ageRange != null || profileImageKey != null;
  }

  public static class AgeRangeDeserializer extends ValueDeserializer<AgeRange> {
    @Override
    public AgeRange deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        return context.reportInputMismatch(AgeRange.class, "ageRange must be a JSON string");
      }
      try {
        return AgeRange.valueOf(parser.getString());
      } catch (IllegalArgumentException e) {
        return context.reportInputMismatch(
            AgeRange.class,
            "ageRange must be one of TEENS, TWENTIES, THIRTIES, FORTIES, FIFTIES_PLUS, UNSPECIFIED");
      }
    }
  }
}
