package com.hobbytree.hobbytree.domain.recommendation.controller;

import com.hobbytree.hobbytree.domain.recommendation.code.RecommendationSuccessCode;
import com.hobbytree.hobbytree.domain.recommendation.dto.CreateRecommendationRequest;
import com.hobbytree.hobbytree.domain.recommendation.dto.CreateRecommendationResponse;
import com.hobbytree.hobbytree.domain.recommendation.dto.LatestRecommendationResponse;
import com.hobbytree.hobbytree.domain.recommendation.service.AiRecommendationService;
import com.hobbytree.hobbytree.domain.recommendation.service.RecommendationService;
import com.hobbytree.hobbytree.global.apiPayload.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {
  private final RecommendationService service;
  private final AiRecommendationService ai;

  @PostMapping
  public ResponseEntity<ApiResponse<CreateRecommendationResponse>> create(
      @AuthenticationPrincipal Long userId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody CreateRecommendationRequest request) {
    return ResponseEntity.status(RecommendationSuccessCode.CREATED.getStatus())
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.onSuccess(
                RecommendationSuccessCode.CREATED,
                ai.hasQuestionnaire(userId)
                    ? ai.create(userId, idempotencyKey, request)
                    : service.create(userId, idempotencyKey, request)));
  }

  @GetMapping("/latest")
  public ResponseEntity<ApiResponse<LatestRecommendationResponse>> latest(
      @AuthenticationPrincipal Long userId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.onSuccess(RecommendationSuccessCode.LATEST, service.getLatest(userId)));
  }
}
