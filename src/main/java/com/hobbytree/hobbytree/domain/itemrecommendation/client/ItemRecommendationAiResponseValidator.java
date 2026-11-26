package com.hobbytree.hobbytree.domain.itemrecommendation.client;

import com.hobbytree.hobbytree.domain.itemrecommendation.code.ItemRecommendationErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.util.List;
import java.util.Set;

final class ItemRecommendationAiResponseValidator {
  private static final Set<String> MATERIAL_STATUSES =
      Set.of(
          "AVAILABLE",
          "FALLBACK_AVAILABLE",
          "NOT_COLLECTED",
          "NO_MATCH",
          "EXPIRED",
          "TEMPORARY_FAILURE",
          "INVALID_CANDIDATES",
          "FILTER_EMPTY",
          "PAGE_EMPTY",
          "CATALOG_CHANGED",
          "STORAGE_NOT_APPROVED");

  private ItemRecommendationAiResponseValidator() {}

  /** Rejects a reply that doesn't echo back this request's activity/material identity in order. */
  static void validate(ItemRecommendationAiRequest request, ItemRecommendationAiResponse reply) {
    check(reply != null && reply.isSuccess() && reply.result() != null);
    var result = reply.result();
    check(request.activityId().equals(result.activityId()));
    check(request.activityContentVersion().equals(result.activityContentVersion()));
    check(
        result.materialResults() != null
            && result.materialResults().size() == request.materials().size());
    for (int i = 0; i < request.materials().size(); i++) {
      var expected = request.materials().get(i);
      var materialResult = result.materialResults().get(i);
      check(materialResult != null);
      var actual = materialResult.material();
      check(actual != null && expected.materialId().equals(actual.materialId()));
      check(actual.name() != null && !actual.name().isBlank());
      check(materialResult.status() != null && MATERIAL_STATUSES.contains(materialResult.status()));
      var products = nullToEmpty(materialResult.products());
      check(products.size() <= request.maxProductsPerMaterial());
      for (var product : products) {
        check(product != null && product.offerRef() != null && !product.offerRef().isBlank());
        check(product.purchaseUrl() != null && product.purchaseUrl().startsWith("https://"));
      }
    }
  }

  private static <T> List<T> nullToEmpty(List<T> values) {
    return values == null ? List.of() : values;
  }

  /** Maps upstream contract violations to the public error without exposing the payload. */
  private static void check(boolean valid) {
    if (!valid) throw new ProjectException(ItemRecommendationErrorCode.AI_BAD_RESPONSE);
  }
}
