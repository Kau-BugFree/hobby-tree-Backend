package com.hobbytree.hobbytree.domain.itemrecommendation.client;

import java.util.List;

// Field names follow the AI contract's camelCase exactly; BE builds materials from the DB, never
// from the FE request, so a caller cannot smuggle in a material the activity doesn't own.
public record ItemRecommendationAiRequest(
    Long activityId,
    Long activityContentVersion,
    String reviewStatus,
    List<Material> materials,
    List<Selection> materialSelections,
    int maxProductsPerMaterial) {

  public record Material(
      Long materialId, String name, String quantity, Boolean isRequired, String acquisitionType) {}

  public record Selection(
      Long materialId, Long budgetKrw, int offset, String catalogRevision, Filters filters) {}

  public record Filters(
      List<String> merchantCodes,
      Long minPriceKrw,
      Long maxPriceKrw,
      List<String> excludeOfferRefs) {}
}
