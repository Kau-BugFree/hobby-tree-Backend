package com.hobbytree.hobbytree.domain.profileimage.controller;

import com.hobbytree.hobbytree.domain.profileimage.dto.IssueUploadUrlRequest;
import com.hobbytree.hobbytree.domain.profileimage.dto.IssueUploadUrlResponse;
import com.hobbytree.hobbytree.domain.profileimage.dto.ResetProfileImageResponse;
import com.hobbytree.hobbytree.domain.profileimage.service.ProfileImageService;
import com.hobbytree.hobbytree.domain.user.code.UserSuccessCode;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/profile-image")
public class ProfileImageController {
  private final ProfileImageService service;

  @PostMapping("/upload-url")
  public ResponseEntity<ApiResponse<IssueUploadUrlResponse>> issueUploadUrl(
      @AuthenticationPrincipal Long userId, @RequestBody IssueUploadUrlRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                UserSuccessCode.PROFILE_IMAGE_UPLOAD_URL_ISSUED,
                service.issueUploadUrl(userId, request)));
  }

  @DeleteMapping
  public ResponseEntity<ApiResponse<ResetProfileImageResponse>> reset(
      @AuthenticationPrincipal Long userId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(UserSuccessCode.PROFILE_IMAGE_RESET, service.reset(userId)));
  }
}
