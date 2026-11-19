package com.hobbytree.hobbytree.domain.activity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record SaveActivityProgressRequest(
    @NotNull @JsonDeserialize(contentUsing = StrictLong.class) List<@NotNull Long> completedStepIds,
    @JsonDeserialize(using = StrictInteger.class) Integer elapsedSeconds,
    @NotNull @Positive @JsonDeserialize(using = StrictLong.class) Long expectedVersion) {
  public static class StrictLong extends ValueDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
        return context.reportInputMismatch(Long.class, "must be a JSON integer");
      return parser.getLongValue();
    }
  }

  public static class StrictInteger extends ValueDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
        return context.reportInputMismatch(Integer.class, "must be a JSON integer");
      return parser.getIntValue();
    }
  }
}
