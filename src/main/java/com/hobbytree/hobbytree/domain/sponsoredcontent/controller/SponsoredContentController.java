package com.hobbytree.hobbytree.domain.sponsoredcontent.controller;

import com.hobbytree.hobbytree.domain.sponsoredcontent.code.SponsoredContentSuccessCode;
import com.hobbytree.hobbytree.domain.sponsoredcontent.dto.ActivitySponsoredContentResponse;
import com.hobbytree.hobbytree.domain.sponsoredcontent.service.SponsoredContentService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/activities")
public class SponsoredContentController {
  private final SponsoredContentService service;

  @GetMapping("/{activityId}/sponsored-content")
  public ResponseEntity<ApiResponse<ActivitySponsoredContentResponse>> getSponsoredContent(
      @AuthenticationPrincipal Long userId, @PathVariable @Positive Long activityId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                SponsoredContentSuccessCode.FOUND, service.findForActivity(userId, activityId)));
  }
}
