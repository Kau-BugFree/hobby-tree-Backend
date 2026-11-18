package com.hobbytree.hobbytree.domain.coach.dto;

import java.util.List;

public record CoachConversationListResponse(
    List<CoachConversationItemResponse> items, String nextCursor, boolean hasNext) {}
