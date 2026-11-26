package com.hobbytree.hobbytree.domain.itemrecommendation.dto;

import java.util.List;

public record ItemRecommendationRequest(
    Long activityContentVersion,
    List<MaterialSelection> materialSelections,
    Integer maxProductsPerMaterial) {

  public record MaterialSelection(
      Long materialId, Long budgetKrw, Integer offset, String catalogRevision, Filters filters) {}

  public record Filters(
      List<String> merchantCodes,
      Long minPriceKrw,
      Long maxPriceKrw,
      List<String> excludeOfferRefs) {}
}
