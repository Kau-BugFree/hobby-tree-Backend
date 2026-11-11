package com.hobbytree.hobbytree.domain.coach.client;

import com.hobbytree.hobbytree.domain.coach.code.CoachErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.util.Set;

final class CoachAiResponseValidator {
  private CoachAiResponseValidator() {}

  /** Rejects malformed, oversized, or cross-conversation results before database mapping. */
  static void validate(CoachAiRequest request, CoachAiResponse reply) {
    check(reply != null && request.conversationId().equals(reply.conversationId()));
    text(reply.answer(), 32000);
    text(reply.answerVersion(), 80);
    text(reply.mode(), 80);
    check(reply.references() != null && reply.references().size() <= 50);
    check(reply.suggestedActivities() != null && reply.suggestedActivities().size() <= 20);
    for (var ref : reply.references()) {
      check(
          ref != null
              && ref.sourceType() != null
              && Set.of("ACTIVITY", "KNOWLEDGE", "RECIPE").contains(ref.sourceType()));
      text(ref.sourceCode(), 200);
      text(ref.title(), 1000);
      if ("RECIPE".equals(ref.sourceType())) check(ref.activityCode() == null);
      else text(ref.activityCode(), 100);
      if ("ACTIVITY".equals(ref.sourceType())) check(ref.sourceCode().equals(ref.activityCode()));
    }
    for (var suggestion : reply.suggestedActivities()) {
      check(suggestion != null);
      text(suggestion.activityCode(), 100);
      text(suggestion.title(), 1000);
      check(suggestion.reason() == null || suggestion.reason().length() <= 4000);
    }
  }

  /** Enforces a required upstream text field's nonblank and storage-length bounds. */
  private static void text(String value, int max) {
    check(value != null && !value.isBlank() && value.length() <= max);
  }

  /** Maps upstream contract violations to the public error without exposing the payload. */
  private static void check(boolean valid) {
    if (!valid) throw new ProjectException(CoachErrorCode.AI_BAD_RESPONSE);
  }
}
