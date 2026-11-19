package com.hobbytree.hobbytree.domain.activity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CompleteActivityRequest(
    @JsonDeserialize(using = StrictString.class) String status,
    @NotNull @Positive @JsonDeserialize(using = SaveActivityProgressRequest.StrictLong.class)
        Long expectedVersion,
    @Valid Feedback feedback,
    @JsonDeserialize(using = StrictString.class) String notDoneReason) {
  public record Feedback(
      @JsonDeserialize(using = SaveActivityProgressRequest.StrictInteger.class)
          Integer satisfactionScore,
      @JsonDeserialize(using = StrictString.class) String perceivedDifficulty,
      @JsonDeserialize(using = StrictString.class) String memo) {}

  public static class StrictString extends ValueDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() != JsonToken.VALUE_STRING)
        return context.reportInputMismatch(String.class, "must be a JSON string");
      return parser.getString();
    }
  }
}
