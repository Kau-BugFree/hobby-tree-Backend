package com.hobbytree.hobbytree.domain.itemrecommendation.client;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ItemRecommendationAiResponse(
    boolean isSuccess, String code, String message, Result result) {

  // Forwarded to FE as-is inside the BE's own ApiResponse.result, per the spec's "result를 한 번만
  // 감싼다" contract - never nested as result.result.
  public record Result(
      Long activityId,
      Long activityContentVersion,
      Long preferencesVersion,
      Instant generatedAt,
      String status,
      boolean onboardingFilteringApplied,
      String sort,
      List<MaterialResult> materialResults,
      String affiliateDisclosure,
      List<String> notices) {}

  public record MaterialResult(
      ItemRecommendationAiRequest.Material material,
      String status,
      String providerStatus,
      List<Product> products,
      String necessityReason,
      String selectionSource,
      String catalogRevision,
      Instant expiresAt,
      Instant lastAttemptAt,
      String displayMessage,
      String fallbackAction,
      int totalCandidates,
      Integer nextOffset,
      int discardedCount,
      Map<String, Object> discardReasons) {}

  public record Product(
      String offerRef,
      String provider,
      String providerProductId,
      String title,
      String displayPrice,
      Long priceKrw,
      String currency,
      String imageUrl,
      String purchaseUrl,
      String merchantCode,
      String merchantName,
      Instant fetchedAt,
      boolean priceIsReference,
      String availability,
      BudgetComparison budgetComparison) {}

  public record BudgetComparison(
      String status, Long budgetKrw, Long differenceKrw, String message, String basis) {}
}
