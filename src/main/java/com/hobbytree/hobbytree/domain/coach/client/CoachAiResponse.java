package com.hobbytree.hobbytree.domain.coach.client;

import java.util.List;

public record CoachAiResponse(
    Long conversationId,
    String answer,
    String answerVersion,
    String mode,
    List<Reference> references,
    List<Suggestion> suggestedActivities,
    boolean replayed) {
  public record Reference(
      String sourceType, String sourceCode, String activityCode, String title) {}

  public record Suggestion(String activityCode, String title, String reason) {}
}
