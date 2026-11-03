package com.hobbytree.hobbytree.domain.notification.controller;

import com.hobbytree.hobbytree.domain.notification.code.NotificationSuccessCode;
import com.hobbytree.hobbytree.domain.notification.dto.NotificationListResponse;
import com.hobbytree.hobbytree.domain.notification.dto.NotificationReadResponse;
import com.hobbytree.hobbytree.domain.notification.service.NotificationService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
public class NotificationController {
  private final NotificationService service;

  @GetMapping
  public ResponseEntity<ApiResponse<NotificationListResponse>> list(
      @AuthenticationPrincipal Long userId,
      @RequestParam(required = false) Boolean isRead,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                NotificationSuccessCode.LIST, service.list(userId, isRead, type, cursor, size)));
  }

  @PatchMapping("/{notificationId}/read")
  public ResponseEntity<ApiResponse<NotificationReadResponse>> markRead(
      @AuthenticationPrincipal Long userId, @PathVariable Long notificationId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                NotificationSuccessCode.READ, service.markRead(userId, notificationId)));
  }
}
