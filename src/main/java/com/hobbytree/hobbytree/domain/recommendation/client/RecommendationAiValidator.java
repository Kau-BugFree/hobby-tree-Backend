package com.hobbytree.hobbytree.domain.recommendation.client;

import com.hobbytree.hobbytree.domain.onboarding.service.QuestionnaireContract;
import com.hobbytree.hobbytree.domain.recommendation.code.RecommendationErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Validates the pinned wire/catalog contract before any recommendation is committed. */
@Component
public class RecommendationAiValidator {
  private static final Set<String> STATES =
      Set.of("recommended", "browse", "needs_review", "no_match");
  private static final Map<String, Integer> MONEY =
      Map.of("0원", 0, "1만 원까지", 10000, "3만 원까지", 30000, "5만 원까지", 50000, "10만 원까지", 100000);
  private static final Map<String, Integer> TIME =
      Map.of("10분", 10, "15분", 15, "30분", 30, "1시간", 60, "2시간", 120);
  private final QuestionnaireContract contract;
  private final Map<String, JsonNode> catalog = new HashMap<>();

  public RecommendationAiValidator(QuestionnaireContract contract) {
    this.contract = contract;
    contract
        .catalog()
        .path("hobbies")
        .forEach(hobby -> catalog.put(hobby.path("id").asText(), hobby));
  }

  public int catalogSize() {
    return catalog.size();
  }

  /** Source activities remain source codes, never guessed application activity IDs. */
  public void validate(JsonNode request, JsonNode result) {
    require(result != null && result.isObject());
    require(
        result
            .propertyNames()
            .equals(
                Set.of(
                    "status",
                    "schema_version",
                    "rules_version",
                    "catalog_version",
                    "recommendations",
                    "excluded",
                    "unsupported_interests",
                    "notices",
                    "follow_up_questions",
                    "preferences",
                    "coverage")));
    String status = result.path("status").asText();
    require(STATES.contains(status));
    require(QuestionnaireContract.VERSION.equals(result.path("schema_version").asText()));
    require(contract.rulesVersion().equals(result.path("rules_version").asText()));
    require(contract.catalogVersion().equals(result.path("catalog_version").asText()));
    JsonNode recs = result.path("recommendations");
    array(recs, catalog.size());
    require("recommended".equals(status) == !recs.isEmpty());
    boolean browse =
        "지금은 취미를 시작할 생각이 없어요".equals(answer(request, "q01"))
            || "안내 없이 직접 찾기".equals(answer(request, "q11"));
    boolean review =
        request.hasNonNull("context_text")
            || selected(request.path("answers").path("q09Exclude")).contains("직접 입력");
    if (browse) require("browse".equals(status));
    else if (review) require("needs_review".equals(status));
    else require(!"browse".equals(status) && !"needs_review".equals(status));
    Set<String> seen = new HashSet<>();
    int rank = 1;
    for (JsonNode rec : recs) {
      String code = rec.path("hobby_id").asText();
      JsonNode hobby = catalog.get(code);
      require(hobby != null && seen.add(code));
      require(rec.path("rank").isIntegralNumber() && rec.path("rank").asInt() == rank++);
      require(
          hobby.path("name").equals(rec.path("name"))
              && hobby.path("ui_key").equals(rec.path("ui_key")));
      decimal(rec.path("score"), 0, 100);
      strings(rec.path("reasons"), 30);
      array(rec.path("breakdown"), 14);
      Set<String> criteria = new HashSet<>();
      for (JsonNode part : rec.path("breakdown")) {
        require(
            Set.of("q02", "q03", "q08", "q09Place", "q10")
                .contains(part.path("question_key").asText()));
        require(criteria.add(part.path("question_key").asText()));
        decimal(part.path("weight"), 0, 100);
        decimal(part.path("fit"), 0, 1);
        decimal(part.path("points"), 0, 100);
        require(part.path("answered").isBoolean());
        strings(part.path("selected"), 30);
      }
      require(criteria.size() == 5);
      array(rec.path("activities"), hobby.path("activities").size());
      require(!rec.path("activities").isEmpty());
      Set<String> activityIds = new HashSet<>();
      for (JsonNode activity : rec.path("activities")) {
        String id = activity.path("activity_id").asText();
        require(activityIds.add(id));
        JsonNode source = null;
        for (JsonNode entry : hobby.path("activities"))
          if (id.equals(entry.path("id").asText())) source = entry;
        require(source != null);
        require(source.path("title").equals(activity.path("title")));
        equalInteger(source, "total_minutes", activity, "total_minutes");
        equalInteger(source, "active_minutes", activity, "active_minutes");
        equalInteger(source, "first_cost_krw", activity, "estimated_first_cost_krw");
        equalInteger(source, "repeat_cost_krw", activity, "estimated_repeat_cost_krw");
        strings(activity.path("confirmation_required"), 50);
        require(
            Set.of("eligible", "needs_confirmation").contains(activity.path("status").asText()));
        require(
            "eligible".equals(activity.path("status").asText())
                == activity.path("confirmation_required").isEmpty());
        boolean unknownResource = false;
        for (JsonNode resource : source.path("required_resources")) {
          JsonNode availability = request.path("resources").path(resource.asText());
          require(!availability.isBoolean() || availability.asBoolean());
          unknownResource |= !availability.isBoolean();
        }
        require(
            selected(activity.path("confirmation_required"))
                .containsAll(selected(source.path("checks"))));
        boolean incompleteLimits =
            limit(request, "q06", "q06_minutes", TIME) == null
                || (source.path("first_cost_krw").asInt() > 0
                    && (limit(request, "q04", "q04_amount_krw", MONEY) == null
                        || limit(request, "q05", "q05_amount_krw", MONEY) == null
                        || !request.hasNonNull("monthly_committed_krw")));
        if (unknownResource
            || incompleteLimits
            || selected(request.path("answers").path("q09Exclude")).isEmpty())
          require("needs_confirmation".equals(activity.path("status").asText()));
        require(
            Collections.disjoint(
                selected(request.path("answers").path("q09Exclude")),
                selected(source.path("exclusion_tags"))));
        limits(request, source);
      }
    }
    array(result.path("excluded"), catalog.size());
    Set<String> excluded = new HashSet<>();
    for (JsonNode item : result.path("excluded")) {
      String code = item.path("hobby_id").asText();
      require(catalog.containsKey(code) && excluded.add(code) && !seen.contains(code));
      require(catalog.get(code).path("name").equals(item.path("name")));
      strings(item.path("reasons"), 100);
    }
    strings(result.path("unsupported_interests"), 30);
    strings(result.path("notices"), 100);
    strings(result.path("follow_up_questions"), 100);
    JsonNode preferences = result.path("preferences");
    require(preferences.isObject() && preferences.path("selection_mode").isTextual());
    require(
        preferences.path("recording_mode").isTextual()
            || preferences.path("recording_mode").isNull());
    require(
        preferences.path("weekly_frequency").isTextual()
            || preferences.path("weekly_frequency").isNull());
    strings(preferences.path("burdens"), 30);
    JsonNode coverage = result.path("coverage");
    decimal(coverage.path("weighted_answer_coverage"), 0, 100);
    require(
        coverage.path("answered_groups").isIntegralNumber()
            && coverage.path("answered_groups").asInt() >= 0
            && coverage.path("answered_groups").asInt() <= 14);
    require(
        coverage.path("total_groups").isIntegralNumber()
            && coverage.path("total_groups").asInt() == 14);
  }

  /** Rejects activities exceeding first-spend, remaining-monthly-budget, or total-time limits. */
  private static void limits(JsonNode request, JsonNode activity) {
    Integer first = limit(request, "q04", "q04_amount_krw", MONEY);
    Integer monthly = limit(request, "q05", "q05_amount_krw", MONEY);
    Integer minutes = limit(request, "q06", "q06_minutes", TIME);
    if (monthly != null && request.hasNonNull("monthly_committed_krw"))
      monthly = Math.max(0, monthly - request.path("monthly_committed_krw").asInt());
    int cost = activity.path("first_cost_krw").asInt();
    require(first == null || cost <= first);
    require(monthly == null || cost <= monthly);
    require(minutes == null || activity.path("total_minutes").asInt() <= minutes);
  }

  /** Resolves a validated custom value or preset; null keeps an unanswered limit unknown. */
  private static Integer limit(
      JsonNode request, String question, String custom, Map<String, Integer> values) {
    return request.path("custom_values").hasNonNull(custom)
        ? request.path("custom_values").path(custom).asInt()
        : values.get(answer(request, question));
  }

  private static String answer(JsonNode request, String key) {
    return request.path("answers").path(key).asText("");
  }

  /** Normalizes scalar/multiple selections for exclusions and confirmation comparisons. */
  private static Set<String> selected(JsonNode node) {
    Set<String> values = new HashSet<>();
    if (node.isTextual()) values.add(node.asText());
    else if (node.isArray()) node.forEach(value -> values.add(value.asText()));
    return values;
  }

  /** Requires an integral response value identical to the pinned catalogue's cost or time. */
  private static void equalInteger(JsonNode left, String a, JsonNode right, String b) {
    require(
        right.path(b).isIntegralNumber()
            && right.path(b).canConvertToLong()
            && left.path(a).asLong() == right.path(b).asLong());
  }

  /** Rejects nonnumeric, nonfinite, or out-of-range scores before persistence. */
  private static void decimal(JsonNode node, double min, double max) {
    require(
        node.isNumber()
            && Double.isFinite(node.asDouble())
            && node.asDouble() >= min
            && node.asDouble() <= max);
  }

  /** Enforces response collection shape and catalogue-derived item limits. */
  private static void array(JsonNode node, int max) {
    require(node.isArray() && node.size() <= max);
  }

  /** Bounds user-visible explanations and requires nonblank string entries. */
  private static void strings(JsonNode node, int max) {
    array(node, max);
    for (JsonNode value : node)
      require(value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 2000);
  }

  /** Converts a response-contract violation to the public gateway error without raw data. */
  private static void require(boolean valid) {
    if (!valid) throw new ProjectException(RecommendationErrorCode.AI_BAD_RESPONSE);
  }
}
