package com.hobbytree.hobbytree.domain.notification.controller;

import com.hobbytree.hobbytree.domain.notification.code.NotificationSubscriptionSuccessCode;
import com.hobbytree.hobbytree.domain.notification.dto.ChatLaunchSubscriptionResponse;
import com.hobbytree.hobbytree.domain.notification.service.NotificationSubscriptionService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notification-subscriptions")
public class NotificationSubscriptionController {
  private final NotificationSubscriptionService service;

  @PostMapping("/chat-launch")
  public ResponseEntity<ApiResponse<ChatLaunchSubscriptionResponse>> subscribeToChatLaunch(
      @AuthenticationPrincipal Long userId) {
    var outcome = service.subscribeToChatLaunch(userId);
    var code =
        outcome.created()
            ? NotificationSubscriptionSuccessCode.CHAT_LAUNCH_SUBSCRIBED
            : NotificationSubscriptionSuccessCode.CHAT_LAUNCH_ALREADY_SUBSCRIBED;
    return ResponseEntity.status(code.getStatus())
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(code, outcome.body()));
  }
}
