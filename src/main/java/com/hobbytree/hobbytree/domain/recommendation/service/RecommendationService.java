package com.hobbytree.hobbytree.domain.recommendation.service;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRepository;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.hobby.entity.Hobby;
import com.hobbytree.hobbytree.domain.hobby.entity.HobbyCategoryCatalog;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyRepository;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyClaim;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyService;
import com.hobbytree.hobbytree.domain.onboarding.dto.BudgetOptionResponse;
import com.hobbytree.hobbytree.domain.onboarding.service.OnboardingService;
import com.hobbytree.hobbytree.domain.recommendation.code.RecommendationErrorCode;
import com.hobbytree.hobbytree.domain.recommendation.dto.CreateRecommendationRequest;
import com.hobbytree.hobbytree.domain.recommendation.dto.CreateRecommendationResponse;
import com.hobbytree.hobbytree.domain.recommendation.dto.LatestRecommendationResponse;
import com.hobbytree.hobbytree.domain.recommendation.dto.RecommendationItemResponse;
import com.hobbytree.hobbytree.domain.recommendation.entity.Recommendation;
import com.hobbytree.hobbytree.domain.recommendation.entity.RecommendationItem;
import com.hobbytree.hobbytree.domain.recommendation.repository.RecommendationItemRepository;
import com.hobbytree.hobbytree.domain.recommendation.repository.RecommendationRepository;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.entity.UserPreference;
import com.hobbytree.hobbytree.domain.user.repository.UserPreferenceRepository;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// API-14 Part 1 (후보 필터링 및 저장 - AI 제외, see hobbytree issue #106): reason/matchedCriteria/
// score below are a rule-based placeholder ("RULE_BASED_V1"), replaced by a real AI call in
// Part 2. AI가 반환한 hobbyId 재검증(로직 3번) 역시 Part 2의 몫이라 여기서는 해당 없음.
@Service
@RequiredArgsConstructor
public class RecommendationService {
  private static final String ENDPOINT = "POST /api/v1/recommendations";
  private static final String RECOMMENDATION_VERSION = "RULE_BASED_V1";
  private static final int DEFAULT_COUNT = 3;
  private static final int MIN_COUNT = 1;
  private static final int MAX_COUNT = 5;
  private static final int MAX_EXCLUDE_IDS = 50;

  private final UserRepository users;
  private final UserPreferenceRepository preferences;
  private final HobbyRepository hobbies;
  private final ActivityRepository activities;
  private final UserHobbyRepository userHobbies;
  private final RecommendationRepository recommendations;
  private final RecommendationItemRepository recommendationItems;
  private final IdempotencyKeyService idempotencyKeys;
  private final ObjectMapper objectMapper;
  private final AiRecommendationService ai;

  /** Preserves legacy-profile recommendations and shared idempotent replay semantics. */
  @Transactional
  public CreateRecommendationResponse create(
      Long userId, String idempotencyKey, CreateRecommendationRequest request) {
    if (idempotencyKey == null || idempotencyKey.isBlank())
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);

    User user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    requireActive(user);

    if (request.preferencesVersion() == null)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    int count = normalizeCount(request.count());
    List<Long> excludeHobbyIds = normalizeExcludeIds(request.excludeHobbyIds());

    String requestHash = RedisAuthStore.hash(canonicalRequest(request, count, excludeHobbyIds));
    IdempotencyKeyClaim claim =
        idempotencyKeys.claim(userId, ENDPOINT, idempotencyKey, requestHash);
    switch (claim.outcome()) {
      case CONFLICT -> throw new ProjectException(GeneralErrorCode.IDEMPOTENCY_KEY_CONFLICT);
      case IN_PROGRESS -> throw new ProjectException(GeneralErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS);
      case REPLAY -> {
        return objectMapper.readValue(claim.responseBody(), CreateRecommendationResponse.class);
      }
      case CLAIMED -> {
        // fall through to build a fresh recommendation below
      }
    }

    try {
      CreateRecommendationResponse response =
          buildRecommendation(user, request.preferencesVersion(), count, excludeHobbyIds);
      idempotencyKeys.complete(
          claim.idempotencyKeyId(),
          claim.ownershipToken(),
          201,
          objectMapper.writeValueAsString(response));
      return response;
    } catch (RuntimeException e) {
      idempotencyKeys.release(claim.idempotencyKeyId(), claim.ownershipToken());
      throw e;
    }
  }

  /** Reads the owner's latest stored result without triggering a fresh recommendation. */
  @Transactional(readOnly = true)
  public LatestRecommendationResponse getLatest(Long userId) {
    User user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    requireActive(user);

    Recommendation latest =
        recommendations
            .findFirstByUser_UserIdOrderByCreatedAtDescRecommendationIdDesc(userId)
            .orElseThrow(() -> new ProjectException(RecommendationErrorCode.LATEST_NOT_FOUND));

    if (latest.getEngineResult() != null) return ai.latest(latest, userId);

    List<RecommendationItem> items =
        recommendationItems.findByRecommendationIdWithHobby(latest.getRecommendationId());
    int requestedCount = items.size();
    // "추천 후 숨김·삭제된 취미는 응답에서 제외" - is_supported=false, the same visibility flag
    // HobbyService.detail/list already filter browsing on.
    List<RecommendationItem> visible =
        items.stream().filter(i -> i.getHobby().getIsSupported()).toList();
    if (visible.isEmpty()) throw new ProjectException(RecommendationErrorCode.LATEST_NOT_FOUND);

    int historicalVersion =
        objectMapper.readTree(latest.getPreferenceSnapshot()).path("preferencesVersion").asInt();
    int currentVersion =
        preferences.findByUser_UserId(userId).map(UserPreference::getPreferencesVersion).orElse(0);

    List<RecommendationItemResponse> itemResponses =
        visible.stream()
            .map(
                i ->
                    new RecommendationItemResponse(
                        i.getHobby().getHobbyId(),
                        i.getHobby().getHobbyName(),
                        i.getHobby().getThumbnailUrl(),
                        i.getRecommendationReason(),
                        parseMatchedCriteria(i.getMatchedCriteria())))
            .toList();

    return new LatestRecommendationResponse(
        latest.getRecommendationId(),
        historicalVersion,
        latest.getCreatedAt(),
        itemResponses,
        requestedCount,
        visible.size(),
        currentVersion != historicalVersion);
  }

  private List<String> parseMatchedCriteria(String json) {
    List<String> result = new ArrayList<>();
    objectMapper.readTree(json).forEach(n -> result.add(n.asText()));
    return result;
  }

  /** Builds legacy candidates only if the expected preference version still uses legacy fields. */
  private CreateRecommendationResponse buildRecommendation(
      User user, int preferencesVersion, int count, List<Long> excludeHobbyIds) {
    Long userId = user.getUserId();
    UserPreference preference =
        preferences
            .findByUser_UserId(userId)
            .filter(p -> p.getPreferencesVersion() != 0)
            .orElseThrow(() -> new ProjectException(RecommendationErrorCode.VERSION_CONFLICT));
    if (preference.getPreferencesVersion() != preferencesVersion)
      throw new ProjectException(RecommendationErrorCode.VERSION_CONFLICT);
    // If the profile changed after controller dispatch, never run the lossy legacy engine.
    if (preference.getQuestionnaire() != null)
      throw new ProjectException(RecommendationErrorCode.VERSION_CONFLICT);

    Set<Integer> preferredCategoryIds = parseCategoryIds(preference.getPreferredCategoryIds());
    BudgetOptionResponse budget =
        OnboardingService.BUDGET_OPTIONS_BY_VALUE.get(preference.getBudgetLevel());

    Set<Long> excludedHobbyIds = new HashSet<>(userHobbies.findAllOwnedHobbyIds(userId));
    excludedHobbyIds.addAll(excludeHobbyIds);

    List<Hobby> supported = hobbies.findCandidates(false, "", false, null, false, null);
    List<Hobby> notOwnedOrExcluded =
        supported.stream().filter(h -> !excludedHobbyIds.contains(h.getHobbyId())).toList();

    List<Long> candidateHobbyIds = notOwnedOrExcluded.stream().map(Hobby::getHobbyId).toList();
    Map<Long, Activity> introByHobbyId = new LinkedHashMap<>();
    for (Activity a : activities.findIntroActivityCandidates(candidateHobbyIds)) {
      introByHobbyId.putIfAbsent(a.getHobbyStage().getHobby().getHobbyId(), a);
    }

    List<ScoredCandidate> scored = new ArrayList<>();
    for (Hobby hobby : notOwnedOrExcluded) {
      Activity intro = introByHobbyId.get(hobby.getHobbyId());
      // No "입문 활동" data to evaluate against - can't be scored, so it's excluded rather
      // than assumed to pass (same posture HobbyService.list takes for its own filters).
      if (intro == null) continue;
      // The only hard preference filter in Part 1's rule-based placeholder: every other
      // preference signal below only affects scoring/matchedCriteria, not membership.
      if (!"ANY".equals(preference.getDifficultyPreference())
          && !preference.getDifficultyPreference().equals(intro.getDifficulty())) continue;

      List<String> matchedCriteria = new ArrayList<>();
      if (budget != null
          && intro.getEstimatedCostMin() != null
          && (budget.monthlyMax() == null || intro.getEstimatedCostMin() <= budget.monthlyMax()))
        matchedCriteria.add("WITHIN_BUDGET");
      if (preference.getAvailableTimeMinutes() != null
          && intro.getExpectedTotalMinutes() != null
          && intro.getExpectedTotalMinutes() <= preference.getAvailableTimeMinutes())
        matchedCriteria.add("WITHIN_TIME");
      if (!"ANY".equals(preference.getIndoorOutdoor())
          && hobby.getIndoorOutdoor().name().equals(preference.getIndoorOutdoor()))
        matchedCriteria.add(hobby.getIndoorOutdoor().name());
      Integer categoryId = HobbyCategoryCatalog.ID_BY_CATEGORY.get(hobby.getCategory());
      if (preferredCategoryIds.contains(categoryId)) matchedCriteria.add("PREFERRED_CATEGORY");
      if ("BEGINNER".equals(intro.getDifficulty())) matchedCriteria.add("BEGINNER_FRIENDLY");

      scored.add(
          new ScoredCandidate(
              hobby, intro, matchedCriteria, BigDecimal.valueOf(matchedCriteria.size())));
    }
    if (scored.isEmpty()) throw new ProjectException(RecommendationErrorCode.NO_CANDIDATES);

    scored.sort(
        Comparator.comparing(ScoredCandidate::score, Comparator.reverseOrder())
            .thenComparing(c -> c.hobby().getHobbyId()));
    List<ScoredCandidate> chosen = scored.size() > count ? scored.subList(0, count) : scored;

    Recommendation recommendation =
        recommendations.saveAndFlush(
            Recommendation.of(user, buildPreferenceSnapshot(preference), RECOMMENDATION_VERSION));

    List<RecommendationItem> items = new ArrayList<>();
    List<RecommendationItemResponse> itemResponses = new ArrayList<>();
    short rankOrder = 1;
    for (ScoredCandidate c : chosen) {
      String reason = buildReason(c.matchedCriteria(), preference);
      items.add(
          RecommendationItem.of(
              recommendation,
              c.hobby(),
              c.intro(),
              rankOrder++,
              c.score(),
              reason,
              objectMapper.writeValueAsString(c.matchedCriteria())));
      itemResponses.add(
          new RecommendationItemResponse(
              c.hobby().getHobbyId(),
              c.hobby().getHobbyName(),
              c.hobby().getThumbnailUrl(),
              reason,
              c.matchedCriteria()));
    }
    recommendationItems.saveAll(items);

    return new CreateRecommendationResponse(
        recommendation.getRecommendationId(),
        preference.getPreferencesVersion(),
        recommendation.getCreatedAt(),
        itemResponses,
        count,
        itemResponses.size());
  }

  private String buildReason(List<String> matchedCriteria, UserPreference preference) {
    if (matchedCriteria.isEmpty()) return "조건에 맞는 취미로 선정되었습니다.";
    List<String> phrases = new ArrayList<>();
    for (String tag : matchedCriteria) {
      switch (tag) {
        case "WITHIN_BUDGET" -> phrases.add("초기 준비 비용이 예산 안에 들어");
        case "WITHIN_TIME" -> phrases.add(preference.getAvailableTimeMinutes() + "분 내로 시작할 수 있어");
        case "INDOOR" -> phrases.add("실내에서 즐길 수 있어");
        case "OUTDOOR" -> phrases.add("실외에서 즐길 수 있어");
        case "PREFERRED_CATEGORY" -> phrases.add("선호하는 분야와 맞아");
        case "BEGINNER_FRIENDLY" -> phrases.add("입문자도 쉽게 시작할 수 있어");
        default -> {}
      }
    }
    return String.join("고 ", phrases) + " 추천합니다.";
  }

  private String buildPreferenceSnapshot(UserPreference preference) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("optionsVersion", preference.getOptionsVersion());
    snapshot.put("budgetLevel", preference.getBudgetLevel());
    snapshot.put("availableTimeMinutes", preference.getAvailableTimeMinutes());
    snapshot.put("indoorOutdoor", preference.getIndoorOutdoor());
    snapshot.put("difficultyPreference", preference.getDifficultyPreference());
    snapshot.put("preferredCategoryIds", parseCategoryIds(preference.getPreferredCategoryIds()));
    snapshot.put("preferencesVersion", preference.getPreferencesVersion());
    return objectMapper.writeValueAsString(snapshot);
  }

  private Set<Integer> parseCategoryIds(String json) {
    String trimmed = json.substring(1, json.length() - 1).strip();
    if (trimmed.isEmpty()) return Set.of();
    return Arrays.stream(trimmed.split(","))
        .map(String::strip)
        .map(Integer::parseInt)
        .collect(Collectors.toSet());
  }

  /** Keeps the public endpoint's default of three and supported range of one to five. */
  static int normalizeCount(Integer requested) {
    if (requested == null) return DEFAULT_COUNT;
    if (requested < MIN_COUNT || requested > MAX_COUNT)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    return requested;
  }

  /** Applies the active-member gate even when invoked outside the common HTTP filter. */
  private static void requireActive(User user) {
    switch (user.getAccountStatus()) {
      case ACTIVE -> {}
      case PENDING_ONBOARDING -> throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);
      case SUSPENDED -> throw new AuthException(AuthErrorCode.SUSPENDED);
      case WITHDRAWN -> throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
      default -> throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    }
  }

  /** Validates explicitly excluded database IDs before hashing or filtering candidates. */
  static List<Long> normalizeExcludeIds(List<Long> requested) {
    if (requested == null) return List.of();
    if (requested.size() > MAX_EXCLUDE_IDS || requested.contains(null))
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    Set<Long> distinct = new HashSet<>(requested);
    if (distinct.size() != requested.size())
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    return requested;
  }

  static String canonicalRequest(
      CreateRecommendationRequest request, int count, List<Long> excludeHobbyIds) {
    String sortedExcludeIds =
        excludeHobbyIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    return "v=" + request.preferencesVersion() + ";count=" + count + ";exclude=" + sortedExcludeIds;
  }

  private record ScoredCandidate(
      Hobby hobby, Activity intro, List<String> matchedCriteria, BigDecimal score) {}
}
