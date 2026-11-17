package com.hobbytree.hobbytree.domain.coach.dto;

// conversationId omitted/null starts a new conversation; userHobbyId is only honored then
// (ignored when continuing an existing conversation) - see CoachService.chat.
public record CoachChatRequest(Long conversationId, String message, Long userHobbyId) {}
