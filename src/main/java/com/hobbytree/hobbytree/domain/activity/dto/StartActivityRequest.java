package com.hobbytree.hobbytree.domain.activity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record StartActivityRequest(
    @NotNull @Positive @JsonDeserialize(using = ActivityIdDeserializer.class) Long activityId) {

  public static class ActivityIdDeserializer extends ValueDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
        return context.reportInputMismatch(Long.class, "must be a JSON integer");
      }
      return parser.getLongValue();
    }
  }
}
