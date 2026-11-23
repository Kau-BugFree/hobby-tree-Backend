package com.hobbytree.hobbytree.domain.itemrecommendation.controller;

import com.hobbytree.hobbytree.domain.itemrecommendation.client.ItemRecommendationAiResponse;
import com.hobbytree.hobbytree.domain.itemrecommendation.code.ItemRecommendationSuccessCode;
import com.hobbytree.hobbytree.domain.itemrecommendation.dto.ItemRecommendationRequest;
import com.hobbytree.hobbytree.domain.itemrecommendation.service.ItemRecommendationService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/activities/{activityId}/item-recommendations")
public class ItemRecommendationController {
  private final ItemRecommendationService service;

  @PostMapping
  public ResponseEntity<ApiResponse<ItemRecommendationAiResponse.Result>> recommend(
      @AuthenticationPrincipal Long userId,
      @PathVariable @Positive Long activityId,
      @RequestBody ItemRecommendationRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                ItemRecommendationSuccessCode.RECOMMENDATIONS,
                service.recommend(userId, activityId, request)));
  }
}
