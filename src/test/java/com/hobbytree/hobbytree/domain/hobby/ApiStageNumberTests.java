package com.hobbytree.hobbytree.domain.hobby;

import static org.assertj.core.api.Assertions.*;

import com.hobbytree.hobbytree.domain.hobby.dto.ApiStageNumber;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ApiStageNumberTests {
  @ParameterizedTest
  @CsvSource({"0,1", "1,2", "2,3", "3,4", "4,5", "5,6"})
  void mapsEachStoredStageToTheNotionNumber(short stored, short external) {
    assertThat(ApiStageNumber.fromStored(stored)).isEqualTo(external);
  }

  @ParameterizedTest
  @ValueSource(shorts = {-32768, -1, 6, 32767})
  void rejectsNumbersOutsideTheStoredStageRange(short stored) {
    assertThatIllegalArgumentException().isThrownBy(() -> ApiStageNumber.fromStored(stored));
  }
}
