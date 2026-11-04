package com.hobbytree.hobbytree.domain.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record ReportRequest(
    @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class) String targetType,
    @NotNull @Positive @JsonDeserialize(using = StrictLongDeserializer.class) Long targetId,
    @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class) String reason,
    @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class) String description) {

  // Reject Jackson's number/boolean-to-string coercion so a non-string token surfaces as
  // COMMON400_1 (type error) instead of silently becoming a string the service goes on to
  // validate as REPORT400_1. Same pattern as SocialLoginRequest.TokenDeserializer.
  public static class StrictStringDeserializer extends ValueDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        return context.reportInputMismatch(String.class, "must be a JSON string");
      }
      return parser.getString();
    }
  }

  // Reject Jackson's default ACCEPT_FLOAT_AS_INT truncation and string-to-long coercion, so
  // targetId=1.5 or targetId="1" surface as COMMON400_1 instead of silently binding to 1.
  public static class StrictLongDeserializer extends ValueDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
        return context.reportInputMismatch(Long.class, "must be a JSON integer");
      }
      return parser.getLongValue();
    }
  }
}
