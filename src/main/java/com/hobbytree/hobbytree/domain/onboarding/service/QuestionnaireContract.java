package com.hobbytree.hobbytree.domain.onboarding.service;

import com.hobbytree.hobbytree.domain.preference.code.PreferenceErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.io.IOException;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pinned FastAPI 9364bfa contract; validating/saving preferences never calls a remote service. */
@Component
public class QuestionnaireContract {
  public static final String VERSION = "onboarding-2026-10-13";
  private static final Set<String> FIELDS =
      Set.of(
          "schema_version",
          "answers",
          "custom_values",
          "context_text",
          "resources",
          "monthly_committed_krw");
  private static final Set<String> CUSTOM =
      Set.of("q04_amount_krw", "q05_amount_krw", "q06_minutes", "q02_other", "q09_exclude_text");
  private final ObjectMapper mapper;
  private final JsonNode definition;
  private final JsonNode catalog;
  private final JsonNode rules;
  private final Map<String, JsonNode> groups = new LinkedHashMap<>();

  public QuestionnaireContract(ObjectMapper mapper) {
    this.mapper = mapper;
    definition = load("questionnaire.json");
    catalog = load("catalog.json");
    rules = load("rules.json");
    definition.path("groups").forEach(group -> groups.put(group.path("key").asText(), group));
    if (!VERSION.equals(definition.path("version").asText()) || groups.size() != 14)
      throw new IllegalStateException("Unexpected pinned questionnaire");
  }

  private JsonNode load(String name) {
    try (var input = new ClassPathResource("onboarding-ai/" + name).getInputStream()) {
      return mapper.readTree(input);
    } catch (IOException failure) {
      throw new IllegalStateException("Missing questionnaire contract", failure);
    }
  }

  /**
   * Public UI definition includes resource codes, never inferred availability or filled answers.
   */
  public JsonNode options() {
    ObjectNode result = (ObjectNode) definition.deepCopy();
    result.set("resources", catalog.path("resources").deepCopy());
    result.put("contextTextMaxLength", 200);
    return result;
  }

  public JsonNode catalog() {
    return catalog.deepCopy();
  }

  public String catalogVersion() {
    return catalog.path("version").asText();
  }

  public String rulesVersion() {
    return rules.path("version").asText();
  }

  /** Canonical container defaults only: unanswered questions/resources remain unknown. */
  public JsonNode validate(JsonNode source) {
    require(source != null && source.isObject());
    require(FIELDS.containsAll(source.propertyNames()));
    if (!VERSION.equals(source.path("schema_version").asText()))
      throw new ProjectException(PreferenceErrorCode.OPTIONS_VERSION_CHANGED);
    ObjectNode result = (ObjectNode) source.deepCopy();
    ObjectNode answers = object(result, "answers");
    ObjectNode custom = object(result, "custom_values");
    ObjectNode resources = object(result, "resources");
    require(
        answers.size() <= 14
            && resources.size() <= 100
            && CUSTOM.containsAll(custom.propertyNames()));
    for (String key : answers.propertyNames()) {
      JsonNode group = groups.get(key), value = answers.get(key);
      require(group != null);
      if (value.isNull()) continue;
      boolean multi = "multi".equals(group.path("kind").asText());
      require(multi ? value.isArray() : value.isTextual());
      List<String> selected = new ArrayList<>();
      if (multi) {
        for (JsonNode item : value) {
          require(item.isTextual());
          selected.add(item.asText());
        }
      } else selected.add(value.asText());
      require(new HashSet<>(selected).size() == selected.size());
      Set<String> allowed = strings(group.path("options"));
      require(allowed.containsAll(selected));
      if (!group.path("max").isNull()) require(selected.size() <= group.path("max").asInt());
      require(
          selected.size() <= 1 || Collections.disjoint(selected, strings(group.path("exclusive"))));
    }
    for (String key : resources.propertyNames())
      require(catalog.path("resources").has(key) && resources.get(key).isBoolean());
    number(custom.get("q04_amount_krw"), 0, 1_000_000_000);
    number(custom.get("q05_amount_krw"), 0, 1_000_000_000);
    number(custom.get("q06_minutes"), 1, 1440);
    number(result.get("monthly_committed_krw"), 0, 1_000_000_000);
    text(custom.get("q02_other"), 200, false);
    text(custom.get("q09_exclude_text"), 500, false);
    text(result.get("context_text"), 200, true);
    if (result.hasNonNull("context_text") && blank(result.get("context_text").asText()))
      result.remove("context_text");
    pair(answers, custom, "q04", "직접 금액 입력", "q04_amount_krw");
    pair(answers, custom, "q05", "직접 금액 입력", "q05_amount_krw");
    pair(answers, custom, "q06", "직접 시간 입력", "q06_minutes");
    pair(answers, custom, "q02", "다른 취미 직접 입력", "q02_other");
    pair(answers, custom, "q09Exclude", "직접 입력", "q09_exclude_text");
    return result;
  }

  private ObjectNode object(ObjectNode root, String name) {
    if (!root.has(name)) root.set(name, mapper.createObjectNode());
    require(root.get(name).isObject());
    return (ObjectNode) root.get(name);
  }

  private static void pair(
      JsonNode answers, JsonNode custom, String key, String option, String field) {
    JsonNode answer = answers.path(key);
    boolean selected =
        answer.isTextual() ? option.equals(answer.asText()) : strings(answer).contains(option);
    require(selected == custom.hasNonNull(field));
  }

  private static void number(JsonNode value, long min, long max) {
    if (value == null || value.isNull()) return;
    require(
        value.isIntegralNumber()
            && value.canConvertToLong()
            && value.asLong() >= min
            && value.asLong() <= max);
  }

  private static void text(JsonNode value, int max, boolean allowBlank) {
    if (value == null || value.isNull()) return;
    require(value.isTextual());
    String text = value.asText();
    int length = text.codePointCount(0, text.length());
    require(length >= 1 && length <= max && (allowBlank || !blank(text)));
  }

  private static boolean blank(String value) {
    return value
        .codePoints()
        .allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85);
  }

  private static Set<String> strings(JsonNode node) {
    Set<String> values = new HashSet<>();
    if (node.isArray()) node.forEach(value -> values.add(value.asText()));
    return values;
  }

  private static void require(boolean valid) {
    if (!valid) throw new ProjectException(PreferenceErrorCode.INVALID_SELECTION);
  }
}
