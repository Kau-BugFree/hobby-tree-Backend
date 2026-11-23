package com.hobbytree.hobbytree.domain.itemrecommendation.service;

import com.hobbytree.hobbytree.domain.activity.code.ActivityErrorCode;
import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import com.hobbytree.hobbytree.domain.activity.entity.ActivityMaterial;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityMaterialRepository;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRepository;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.itemrecommendation.client.ItemRecommendationAiRequest;
import com.hobbytree.hobbytree.domain.itemrecommendation.code.ItemRecommendationErrorCode;
import com.hobbytree.hobbytree.domain.itemrecommendation.dto.ItemRecommendationRequest;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the activity/materials DB snapshot and builds the AI request inside one short transaction,
 * separate from {@link ItemRecommendationService} so the outer orchestration can run the AI HTTP
 * call without holding this transaction open (mirrors CoachTurnTransactions).
 */
@Service
@RequiredArgsConstructor
class ItemRecommendationSnapshot {
  private static final int MAX_MATERIALS = 20;
  private static final int MAX_MATERIAL_SELECTIONS = 20;
  private static final int MIN_MAX_PRODUCTS_PER_MATERIAL = 1;
  private static final int MAX_MAX_PRODUCTS_PER_MATERIAL = 5;
  private static final int DEFAULT_MAX_PRODUCTS_PER_MATERIAL = 3;
  private static final int MAX_MERCHANT_CODES = 10;
  private static final int MAX_EXCLUDE_OFFER_REFS = 100;
  private static final long MAX_PRICE_KRW = 1_000_000_000_000L;

  private final UserRepository users;
  private final ActivityRepository activities;
  private final ActivityMaterialRepository materials;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  ItemRecommendationAiRequest read(
      Long userId, Long activityId, ItemRecommendationRequest request) {
    requireActiveUser(userId);
    Activity activity =
        activities
            .findPublicDetail(activityId)
            .orElseThrow(() -> new ProjectException(ActivityErrorCode.NOT_FOUND));
    if (request.activityContentVersion() == null
        || !request.activityContentVersion().equals(activity.getContentVersion()))
      throw new ProjectException(ItemRecommendationErrorCode.CONTENT_VERSION_CHANGED);

    List<ActivityMaterial> rows =
        materials.findByActivity_ActivityIdOrderByActivityMaterialIdAsc(activityId);
    // 20 is the AI contract's hard ceiling (materials[] 0~20). BE has no split/merge policy for
    // activities above it yet (integration-todo.md BE-08), so reject rather than silently truncate.
    if (rows.size() > MAX_MATERIALS)
      throw new ProjectException(ItemRecommendationErrorCode.TOO_MANY_MATERIALS);

    List<ItemRecommendationAiRequest.Material> materialInputs =
        rows.stream()
            .map(
                m ->
                    new ItemRecommendationAiRequest.Material(
                        m.getActivityMaterialId(),
                        m.getMaterialName(),
                        m.getQuantityDescription(),
                        m.getIsRequired(),
                        m.getAcquisitionType()))
            .toList();
    Set<Long> materialIds = new HashSet<>();
    for (var m : materialInputs) materialIds.add(m.materialId());

    return new ItemRecommendationAiRequest(
        activityId,
        activity.getContentVersion(),
        "PUBLISHED",
        materialInputs,
        toSelections(request.materialSelections(), materialIds),
        parseMaxProductsPerMaterial(request.maxProductsPerMaterial()));
  }

  // Rejects any selection whose materialId doesn't belong to this activity's own DB materials -
  // the FE never gets to pick which material IDs the AI call carries.
  private List<ItemRecommendationAiRequest.Selection> toSelections(
      List<ItemRecommendationRequest.MaterialSelection> input, Set<Long> materialIds) {
    if (input == null) return List.of();
    if (input.size() > MAX_MATERIAL_SELECTIONS)
      throw new ProjectException(ItemRecommendationErrorCode.INVALID_SELECTION);
    Set<Long> seen = new HashSet<>();
    return input.stream()
        .map(
            selection -> {
              if (selection == null)
                throw new ProjectException(ItemRecommendationErrorCode.INVALID_SELECTION);
              Long materialId = selection.materialId();
              if (materialId == null || !materialIds.contains(materialId) || !seen.add(materialId))
                throw new ProjectException(ItemRecommendationErrorCode.INVALID_SELECTION);
              int offset = selection.offset() == null ? 0 : selection.offset();
              if (offset < 0 || offset > 1000)
                throw new ProjectException(ItemRecommendationErrorCode.INVALID_SELECTION);
              return new ItemRecommendationAiRequest.Selection(
                  materialId,
                  requirePriceRange(selection.budgetKrw()),
                  offset,
                  requireBoundedText(selection.catalogRevision(), 100),
                  toFilters(selection.filters()));
            })
        .toList();
  }

  private ItemRecommendationAiRequest.Filters toFilters(ItemRecommendationRequest.Filters filters) {
    if (filters == null)
      return new ItemRecommendationAiRequest.Filters(List.of(), null, null, List.of());
    List<String> merchantCodes = requireBoundedTexts(filters.merchantCodes(), MAX_MERCHANT_CODES);
    List<String> excludeOfferRefs =
        requireBoundedTexts(filters.excludeOfferRefs(), MAX_EXCLUDE_OFFER_REFS);
    Long minPriceKrw = requirePriceRange(filters.minPriceKrw());
    Long maxPriceKrw = requirePriceRange(filters.maxPriceKrw());
    if (minPriceKrw != null && maxPriceKrw != null && maxPriceKrw < minPriceKrw)
      throw new ProjectException(ItemRecommendationErrorCode.INVALID_SELECTION);
    return new ItemRecommendationAiRequest.Filters(
        merchantCodes, minPriceKrw, maxPriceKrw, excludeOfferRefs);
  }

  private List<String> requireBoundedTexts(List<String> values, int maxCount) {
    if (values == null) return List.of();
    if (values.size() > maxCount)
      throw new ProjectException(ItemRecommendationErrorCode.INVALID_SELECTION);
    return values.stream().map(v -> requireBoundedText(v, 100)).toList();
  }

  private String requireBoundedText(String value, int max) {
    if (value == null) return null;
    if (value.isBlank() || value.length() > max)
      throw new ProjectException(ItemRecommendationErrorCode.INVALID_SELECTION);
    return value;
  }

  private Long requirePriceRange(Long value) {
    if (value == null) return null;
    if (value < 0 || value > MAX_PRICE_KRW)
      throw new ProjectException(ItemRecommendationErrorCode.INVALID_SELECTION);
    return value;
  }

  private int parseMaxProductsPerMaterial(Integer value) {
    if (value == null) return DEFAULT_MAX_PRODUCTS_PER_MATERIAL;
    if (value < MIN_MAX_PRODUCTS_PER_MATERIAL || value > MAX_MAX_PRODUCTS_PER_MATERIAL)
      throw new ProjectException(ItemRecommendationErrorCode.INVALID_SELECTION);
    return value;
  }

  // Mirrors ActivityDetailService's exact account-status gate so this endpoint's access rules
  // never drift from the activity detail page that feeds it its activityId/contentVersion.
  private void requireActiveUser(Long userId) {
    if (userId == null) throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    var user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    switch (user.getAccountStatus()) {
      case ACTIVE -> {}
      case PENDING_ONBOARDING -> throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);
      case SUSPENDED -> throw new AuthException(AuthErrorCode.SUSPENDED);
      case WITHDRAWN -> throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
      default -> throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    }
  }
}
