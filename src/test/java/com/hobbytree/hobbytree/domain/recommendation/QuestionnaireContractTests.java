package com.hobbytree.hobbytree.domain.recommendation;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.onboarding.service.QuestionnaireContract;
import com.hobbytree.hobbytree.domain.recommendation.client.RecommendationAiValidator;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class QuestionnaireContractTests {
  private final ObjectMapper mapper = new ObjectMapper();
  private final QuestionnaireContract contract = new QuestionnaireContract(mapper);
  private final RecommendationAiValidator validator = new RecommendationAiValidator(contract);

  @Test
  void acceptsPinnedRequestAndResponseWithoutInventingResourceAvailability() throws IOException {
    ObjectNode request = fixture("request");
    request.remove("top_k");
    assertThat(contract.validate(request)).isEqualTo(request);
    assertThat(request.path("resources").has("library_access")).isFalse();
    assertThatCode(() -> validator.validate(request, fixture("response")))
        .doesNotThrowAnyException();
    assertThat(contract.options().path("groups")).hasSize(14);
  }

  @Test
  void absentContainersDefaultToEmptyAndNullAnswersRemainUnknown() {
    var request = mapper.createObjectNode().put("schema_version", QuestionnaireContract.VERSION);
    var normalized = contract.validate(request);
    assertThat(normalized.path("answers").isEmpty()).isTrue();
    assertThat(normalized.path("resources").isEmpty()).isTrue();
    request.putObject("answers").putNull("q04");
    assertThat(contract.validate(request).path("answers").path("q04").isNull()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"answers\":null",
        "\"resources\":{\"smartphone\":null}",
        "\"resources\":{\"smartphone\":\"true\"}",
        "\"resources\":{\"unknown\":true}",
        "\"answers\":{\"q99\":null}",
        "\"answers\":{\"q10\":[\"혼자\"]}",
        "\"answers\":{\"q13\":[\"없음\",\"없음\"]}",
        "\"answers\":{\"q02\":[\"독서\",\"아직 잘 모르겠어요\"]}",
        "\"monthly_committed_krw\":\"100\"",
        "\"monthly_committed_krw\":1.5",
        "\"monthly_committed_krw\":1000000001",
        "\"custom_values\":{\"q04_amount_krw\":100}",
        "\"answers\":{\"q04\":\"직접 금액 입력\"}",
        "\"top_k\":3",
        "\"answers\":{\"q04\":\"직접 금액 입력\"},\"custom_values\":{\"q04_amount_krw\":true}"
      })
  void rejectsMalformedOrAmbiguousAnswers(String fields) {
    var input =
        mapper.readTree(
            "{\"schema_version\":\"" + QuestionnaireContract.VERSION + "\"," + fields + "}");
    assertThatThrownBy(() -> contract.validate(input)).isInstanceOf(ProjectException.class);
  }

  @Test
  void validatesCustomPairsAndUnicodeCodePointLimit() {
    var request = mapper.createObjectNode().put("schema_version", QuestionnaireContract.VERSION);
    request.putObject("answers").put("q04", "직접 금액 입력");
    request.putObject("custom_values").put("q04_amount_krw", 0);
    request.put("context_text", "😀".repeat(200));
    assertThatCode(() -> contract.validate(request)).doesNotThrowAnyException();
    request.put("context_text", "😀".repeat(201));
    assertThatThrownBy(() -> contract.validate(request)).isInstanceOf(ProjectException.class);
    request.put("context_text", "\u00a0\u0085");
    assertThat(contract.validate(request).has("context_text")).isFalse();
  }

  @Test
  void rejectsUntrustedResponseCodesVersionsAndHardConstraintViolations() throws IOException {
    var request = fixture("request");
    var result = fixture("response");
    ((ObjectNode) result.path("recommendations").get(0)).put("hobby_id", "invented");
    assertThatThrownBy(() -> validator.validate(request, result))
        .isInstanceOf(ProjectException.class);
    var version = fixture("response").put("catalog_version", "future");
    assertThatThrownBy(() -> validator.validate(request, version))
        .isInstanceOf(ProjectException.class);
    var valid = fixture("response");
    ((ObjectNode) request.path("resources")).put("owned_book", false);
    assertThatThrownBy(() -> validator.validate(request, valid))
        .isInstanceOf(ProjectException.class);
  }

  /** Loads pinned example data while closing the classpath stream after parsing. */
  private ObjectNode fixture(String name) throws IOException {
    try (var input =
        new ClassPathResource("recommendation-ai/" + name + ".json").getInputStream()) {
      return (ObjectNode) mapper.readTree(input);
    }
  }
}
