package com.hobbytree.hobbytree.domain.recommendation.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.hobby.entity.Hobby;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyRepository;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyClaim;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyService;
import com.hobbytree.hobbytree.domain.onboarding.service.QuestionnaireContract;
import com.hobbytree.hobbytree.domain.recommendation.client.*;
import com.hobbytree.hobbytree.domain.recommendation.code.RecommendationErrorCode;
import com.hobbytree.hobbytree.domain.recommendation.dto.*;
import com.hobbytree.hobbytree.domain.recommendation.entity.*;
import com.hobbytree.hobbytree.domain.recommendation.repository.*;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.entity.UserPreference;
import com.hobbytree.hobbytree.domain.user.repository.*;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Stateless FastAPI calculation between short prepare/finalize transactions; no external LLM. */
@Service
public class AiRecommendationService {
  private static final String ENDPOINT = "POST /api/v1/recommendations";
  private final UserRepository users;
  private final UserPreferenceRepository preferences;
  private final HobbyRepository hobbies;
  private final UserHobbyRepository trees;
  private final RecommendationRepository recommendations;
  private final RecommendationItemRepository items;
  private final IdempotencyKeyService keys;
  private final ObjectMapper mapper;
  private final RecommendationAiClient client;
  private final RecommendationAiValidator validator;
  private final QuestionnaireContract contract;
  private final EntityManager entityManager;
  private final TransactionTemplate transactions;

  public AiRecommendationService(
      UserRepository users,
      UserPreferenceRepository preferences,
      HobbyRepository hobbies,
      UserHobbyRepository trees,
      RecommendationRepository recommendations,
      RecommendationItemRepository items,
      IdempotencyKeyService keys,
      ObjectMapper mapper,
      RecommendationAiClient client,
      RecommendationAiValidator validator,
      QuestionnaireContract contract,
      EntityManager entityManager,
      PlatformTransactionManager transactionManager) {
    this.users = users;
    this.preferences = preferences;
    this.hobbies = hobbies;
    this.trees = trees;
    this.recommendations = recommendations;
    this.items = items;
    this.keys = keys;
    this.mapper = mapper;
    this.client = client;
    this.validator = validator;
    this.contract = contract;
    this.entityManager = entityManager;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Transactional(readOnly = true)
  public boolean hasQuestionnaire(Long userId) {
    return preferences
        .findByUser_UserId(userId)
        .map(p -> p.getQuestionnaire() != null)
        .orElse(false);
  }

  /** Claim hashing matches the legacy endpoint so replay survives a later profile-mode change. */
  public CreateRecommendationResponse create(
      Long userId, String key, CreateRecommendationRequest request) {
    if (key == null || key.isBlank() || request.preferencesVersion() == null)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    int count = RecommendationService.normalizeCount(request.count());
    var excluded = RecommendationService.normalizeExcludeIds(request.excludeHobbyIds());
    transactions.executeWithoutResult(status -> active(userId));
    var claim =
        keys.claim(
            userId,
            ENDPOINT,
            key,
            RedisAuthStore.hash(RecommendationService.canonicalRequest(request, count, excluded)));
    switch (claim.outcome()) {
      case CONFLICT -> throw new ProjectException(GeneralErrorCode.IDEMPOTENCY_KEY_CONFLICT);
      case IN_PROGRESS -> throw new ProjectException(GeneralErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS);
      case REPLAY -> {
        return mapper.readValue(claim.responseBody(), CreateRecommendationResponse.class);
      }
      case CLAIMED -> {}
    }
    try {
      String saved =
          transactions.execute(
              status -> {
                active(userId);
                return currentPreference(userId, request.preferencesVersion()).getQuestionnaire();
              });
      ObjectNode payload = (ObjectNode) contract.validate(mapper.readTree(saved));
      // Fetch the small full candidate set, then apply owned/excluded IDs before truncating.
      payload.put("top_k", Math.max(count, validator.catalogSize()));
      JsonNode result = client.recommend(payload);
      validator.validate(payload, result);
      return transactions.execute(
          status ->
              finish(userId, request.preferencesVersion(), saved, count, excluded, result, claim));
    } catch (RuntimeException failure) {
      keys.release(claim.idempotencyKeyId(), claim.ownershipToken());
      throw failure;
    }
  }

  /**
   * No result or idempotency completion can commit if account/preference/claim changed in flight.
   */
  private CreateRecommendationResponse finish(
      Long userId,
      int version,
      String saved,
      int count,
      List<Long> excluded,
      JsonNode result,
      IdempotencyKeyClaim claim) {
    User user = active(userId);
    var preference = currentPreference(userId, version);
    if (!mapper.readTree(saved).equals(mapper.readTree(preference.getQuestionnaire())))
      throw new ProjectException(RecommendationErrorCode.VERSION_CONFLICT);
    Set<Long> blocked = new HashSet<>(trees.findAllOwnedHobbyIds(userId));
    blocked.addAll(excluded);
    Map<String, Hobby> byCode = new HashMap<>();
    for (JsonNode entry : result.path("recommendations")) {
      String code = entry.path("hobby_id").asText();
      Hobby hobby =
          hobbies
              .findByHobbyCode(code)
              .orElseThrow(() -> new ProjectException(RecommendationErrorCode.AI_BAD_RESPONSE));
      if (Boolean.TRUE.equals(hobby.getIsSupported()) && !blocked.contains(hobby.getHobbyId())) {
        var locked = hobbies.findForPlanting(hobby.getHobbyId());
        if (locked.isPresent()) {
          entityManager.refresh(locked.get());
          byCode.put(code, locked.get());
        }
      }
    }
    var filtered = filter(result, byCode.keySet(), count);
    String snapshot =
        mapper.writeValueAsString(
            Map.of(
                "preferencesVersion",
                version,
                "requestedCount",
                count,
                "questionnaire",
                mapper.readTree(saved)));
    var recommendation =
        Recommendation.of(
            user, snapshot, "FASTAPI:" + contract.rulesVersion() + "/" + contract.catalogVersion());
    recommendation.recordEngineResult(mapper.writeValueAsString(filtered));
    recommendations.saveAndFlush(recommendation);
    List<RecommendationItemResponse> responses = new ArrayList<>();
    short rank = 1;
    for (JsonNode entry : filtered.path("recommendations")) {
      Hobby hobby = byCode.get(entry.path("hobby_id").asText());
      StringJoiner reason = new StringJoiner(" ");
      entry.path("reasons").forEach(value -> reason.add(value.asText()));
      BigDecimal score =
          BigDecimal.valueOf(entry.path("score").asDouble()).setScale(2, RoundingMode.HALF_UP);
      var item =
          RecommendationItem.of(
              recommendation, hobby, null, rank++, score, reason.toString(), "[]");
      items.save(item);
      responses.add(itemResponse(item));
    }
    var response =
        new CreateRecommendationResponse(
            recommendation.getRecommendationId(),
            version,
            recommendation.getCreatedAt(),
            responses,
            count,
            responses.size(),
            filtered);
    keys.complete(
        claim.idempotencyKeyId(), claim.ownershipToken(), 201, mapper.writeValueAsString(response));
    return response;
  }

  /** Called inside the existing API-15 read transaction, including historical non-match results. */
  public LatestRecommendationResponse latest(Recommendation latest, Long userId) {
    var visible =
        items.findByRecommendationIdWithHobby(latest.getRecommendationId()).stream()
            .filter(item -> Boolean.TRUE.equals(item.getHobby().getIsSupported()))
            .toList();
    Set<String> codes = new HashSet<>();
    visible.forEach(item -> codes.add(item.getHobby().getHobbyCode()));
    JsonNode snapshot = mapper.readTree(latest.getPreferenceSnapshot());
    int requested = snapshot.path("requestedCount").asInt();
    int oldVersion = snapshot.path("preferencesVersion").asInt();
    int currentVersion =
        preferences.findByUser_UserId(userId).map(UserPreference::getPreferencesVersion).orElse(0);
    var result = filter(mapper.readTree(latest.getEngineResult()), codes, requested);
    return new LatestRecommendationResponse(
        latest.getRecommendationId(),
        oldVersion,
        latest.getCreatedAt(),
        visible.stream().map(this::itemResponse).toList(),
        requested,
        visible.size(),
        oldVersion != currentVersion,
        result);
  }

  private RecommendationItemResponse itemResponse(RecommendationItem item) {
    Hobby hobby = item.getHobby();
    return new RecommendationItemResponse(
        hobby.getHobbyId(),
        hobby.getHobbyName(),
        hobby.getThumbnailUrl(),
        item.getRecommendationReason(),
        List.of(),
        hobby.getHobbyCode(),
        item.getScore());
  }

  private ObjectNode filter(JsonNode source, Set<String> visible, int count) {
    ObjectNode result = (ObjectNode) source.deepCopy();
    var selected = mapper.createArrayNode();
    for (JsonNode entry : source.path("recommendations")) {
      if (visible.contains(entry.path("hobby_id").asText()) && selected.size() < count) {
        ObjectNode item = (ObjectNode) entry.deepCopy();
        item.put("rank", selected.size() + 1);
        selected.add(item);
      }
    }
    result.set("recommendations", selected);
    // FastAPI collects confirmations from each first activity; recompute after app-side filtering.
    if ("recommended".equals(source.path("status").asText())) {
      Set<String> questions = new LinkedHashSet<>();
      selected.forEach(
          entry ->
              entry
                  .path("activities")
                  .get(0)
                  .path("confirmation_required")
                  .forEach(question -> questions.add(question.asText())));
      var followUps = result.putArray("follow_up_questions");
      questions.forEach(followUps::add);
    }
    // The standalone engine does not persist inputs, but this Spring endpoint deliberately does.
    var notices = result.putArray("notices");
    source
        .path("notices")
        .forEach(
            notice ->
                notices.add(
                    notice
                        .asText()
                        .replace("입력은 이 응답에 반환하며 서버에는 저장하지 않습니다.", "입력은 취향 설정과 추천 결과에 저장됩니다.")));
    if (selected.isEmpty() && "recommended".equals(result.path("status").asText())) {
      result.put("status", "no_match");
      ((tools.jackson.databind.node.ArrayNode) result.path("notices"))
          .add("이미 보유했거나 제외했거나 현재 제공되지 않는 취미를 제외하면 추천 후보가 없습니다.");
    }
    return result;
  }

  private UserPreference currentPreference(Long userId, int version) {
    var preference =
        preferences
            .findByUser_UserId(userId)
            .orElseThrow(() -> new ProjectException(RecommendationErrorCode.VERSION_CONFLICT));
    entityManager.refresh(preference);
    if (version < 1
        || preference.getPreferencesVersion() != version
        || preference.getQuestionnaire() == null)
      throw new ProjectException(RecommendationErrorCode.VERSION_CONFLICT);
    return preference;
  }

  private User active(Long userId) {
    User user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    entityManager.refresh(user);
    switch (user.getAccountStatus()) {
      case ACTIVE -> {
        return user;
      }
      case PENDING_ONBOARDING -> throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);
      case SUSPENDED -> throw new AuthException(AuthErrorCode.SUSPENDED);
      case WITHDRAWN -> throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
      default -> throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    }
  }
}
