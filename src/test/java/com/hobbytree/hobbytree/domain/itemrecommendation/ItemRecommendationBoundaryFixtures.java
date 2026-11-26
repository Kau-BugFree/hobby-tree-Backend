package com.hobbytree.hobbytree.domain.itemrecommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.hobbytree.hobbytree.domain.itemrecommendation.client.ItemRecommendationAiRequest;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic catalog only: never calls a supplier or stores real product data. */
public final class ItemRecommendationBoundaryFixtures {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ItemRecommendationBoundaryFixtures() {}

  public static ItemRecommendationAiRequest request(int materials, int limit) {
    var rows = new ArrayList<ItemRecommendationAiRequest.Material>();
    for (long id = 91001; id < 91001 + materials; id++) {
      rows.add(new ItemRecommendationAiRequest.Material(id, "합성 준비물 " + id, "1개", true, null));
    }
    return new ItemRecommendationAiRequest(9001L, 3L, "PUBLISHED", rows, List.of(), limit);
  }

  public static ObjectNode response(ItemRecommendationAiRequest input, int productsPerMaterial) {
    var root = MAPPER.createObjectNode();
    root.put("isSuccess", true)
        .put("code", "ITEM_RECOMMENDATIONS_PROCESSED")
        .put("message", "합성 응답");
    var result = root.putObject("result");
    result
        .put("activityId", input.activityId())
        .put("activityContentVersion", input.activityContentVersion());
    result.put("preferencesVersion", 0).put("generatedAt", "2026-11-25T00:00:00Z");
    result.put("status", "AVAILABLE").put("onboardingFilteringApplied", false);
    result.put("sort", "PRICE_ASC_NULLS_LAST").put("affiliateDisclosure", "합성 제휴 고지");
    result.putArray("notices").add("가격·재고는 테스트용이며 실제 상품이 아닙니다.");
    var rows = result.putArray("materialResults");
    for (var material : input.materials()) {
      var row = rows.addObject();
      row.set("material", MAPPER.valueToTree(material));
      row.put("status", "AVAILABLE").put("providerStatus", "SUCCESS");
      row.putNull("necessityReason");
      row.put("selectionSource", "PRIMARY").put("catalogRevision", "synthetic-revision");
      row.put("expiresAt", "2026-11-26T00:00:00Z").put("lastAttemptAt", "2026-11-25T00:00:00Z");
      row.put("displayMessage", "합성 상품 목록").put("fallbackAction", "NONE");
      row.put("totalCandidates", productsPerMaterial).putNull("nextOffset");
      row.put("discardedCount", 0).putObject("discardReasons");
      var products = row.putArray("products");
      for (int i = 0; i < productsPerMaterial; i++) {
        var product = products.addObject();
        product.put("offerRef", "synthetic_" + material.materialId() + "_" + i);
        product.put("provider", "ADPICK").putNull("providerProductId");
        product.put("title", "한글 합성상품 ".repeat(20));
        product.put("currency", "KRW").putNull("imageUrl");
        product.put(
            "purchaseUrl", "https://shop.example.invalid/p/" + material.materialId() + "/" + i);
        product.put("merchantCode", "SYNTHETIC").put("merchantName", "합성 판매처");
        product
            .put("fetchedAt", "2026-11-25T00:00:00Z")
            .put("priceIsReference", true)
            .put("availability", "UNKNOWN");
        var budget = product.putObject("budgetComparison");
        budget.putNull("budgetKrw").putNull("differenceKrw");
        budget.put("basis", "DISPLAYED_PRODUCT_PRICE_EXCLUDING_UNCONFIRMED_SHIPPING_AND_OPTIONS");
        if (i == productsPerMaterial - 1) {
          product.putNull("priceKrw").put("displayPrice", "판매처에서 가격 확인");
          budget.put("status", "PRICE_UNKNOWN").put("message", "가격 미확인");
        } else {
          product.put("priceKrw", (i + 1) * 1000L).put("displayPrice", (i + 1) + ",000원");
          budget.put("status", "NOT_SET").put("message", "예산 미설정");
        }
      }
    }
    return root;
  }

  public static String sizedResponse(int targetBytes) {
    var root = response(request(20, 5), 5);
    int remaining = targetBytes - MAPPER.writeValueAsBytes(root).length;
    assertThat(remaining).isPositive();
    for (var row : root.path("result").path("materialResults")) {
      for (var node : row.path("products")) {
        var product = (ObjectNode) node;
        String url = product.path("purchaseUrl").asString();
        int padding = Math.min(remaining, 4096 - url.length());
        product.put("purchaseUrl", url + "a".repeat(padding));
        remaining -= padding;
        assertThat(product.path("purchaseUrl").asString().length()).isLessThanOrEqualTo(4096);
      }
    }
    assertThat(remaining).isZero();
    assertThat(MAPPER.writeValueAsBytes(root)).hasSize(targetBytes);
    return MAPPER.writeValueAsString(root);
  }
}
