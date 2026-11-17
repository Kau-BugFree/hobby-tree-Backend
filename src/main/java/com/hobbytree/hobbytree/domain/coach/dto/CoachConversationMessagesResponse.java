package com.hobbytree.hobbytree.domain.coach.dto;

import java.util.List;

public record CoachConversationMessagesResponse(
    Long conversationId,
    Long userHobbyId,
    Long hobbyId,
    List<CoachMessageItemResponse> items,
    String nextCursor,
    boolean hasNext) {}
