package com.hobbytree.hobbytree.domain.coach.controller;

import com.hobbytree.hobbytree.domain.coach.code.CoachSuccessCode;
import com.hobbytree.hobbytree.domain.coach.dto.CoachChatRequest;
import com.hobbytree.hobbytree.domain.coach.dto.CoachChatResponse;
import com.hobbytree.hobbytree.domain.coach.dto.CoachConversationListResponse;
import com.hobbytree.hobbytree.domain.coach.dto.CoachConversationMessagesResponse;
import com.hobbytree.hobbytree.domain.coach.service.CoachService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/coach")
public class CoachController {
  private final CoachService service;

  @PostMapping("/chat")
  public ResponseEntity<ApiResponse<CoachChatResponse>> chat(
      @AuthenticationPrincipal Long userId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody CoachChatRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                CoachSuccessCode.CHAT, service.chat(userId, idempotencyKey, request)));
  }

  @GetMapping("/conversations")
  public ResponseEntity<ApiResponse<CoachConversationListResponse>> conversations(
      @AuthenticationPrincipal Long userId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                CoachSuccessCode.CONVERSATIONS, service.listConversations(userId, cursor, size)));
  }

  @GetMapping("/conversations/{conversationId}/messages")
  public ResponseEntity<ApiResponse<CoachConversationMessagesResponse>> messages(
      @AuthenticationPrincipal Long userId,
      @PathVariable Long conversationId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                CoachSuccessCode.MESSAGES,
                service.listMessages(userId, conversationId, cursor, size)));
  }
}
