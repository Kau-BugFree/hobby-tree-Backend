package com.hobbytree.hobbytree.domain.localbusiness;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hobbytree.hobbytree.domain.localbusiness.client.NaverLocalSearchClient;
import com.hobbytree.hobbytree.domain.localbusiness.client.NaverLocalSearchClient.NaverLocalItem;
import com.hobbytree.hobbytree.domain.localbusiness.service.LocalBusinessSearchService;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalBusinessSearchServiceTests {
  NaverLocalSearchClient client = mock(NaverLocalSearchClient.class);
  LocalBusinessSearchService service =
      new LocalBusinessSearchService(
          client, mock(com.hobbytree.hobbytree.global.ratelimit.RateLimiter.class));

  @BeforeEach
  void setup() {
    when(client.search(anyString()))
        .thenReturn(
            List.of(
                new NaverLocalItem(
                    "맛있는 요리 교실",
                    "교육>요리학원",
                    "설명",
                    "02-123-4567",
                    "서울 강남구",
                    "서울 강남구 테헤란로",
                    "1",
                    "2")));
  }

  @Test
  void combinesRegionAndFixedKoreanKeywordIntoTheQuery() {
    service.search(1L, "강남구", "COOKING");
    verify(client).search("강남구 요리교실");
  }

  @Test
  void mapsEachKeywordToItsOwnKoreanTerm() {
    service.search(1L, "홍대", "INSTRUMENT");
    verify(client).search("홍대 악기학원");
    service.search(1L, "홍대", "READING");
    verify(client).search("홍대 독서모임");
  }

  @Test
  void trimsTheRegionBeforeBuildingTheQuery() {
    service.search(1L, "  강남구  ", "COOKING");
    verify(client).search("강남구 요리교실");
  }

  @Test
  void mapsClientItemsIntoTheResponseFieldByField() {
    var result = service.search(1L, "강남구", "COOKING");
    assertThat(result.region()).isEqualTo("강남구");
    assertThat(result.hobby()).isEqualTo("COOKING");
    assertThat(result.items()).hasSize(1);
    var item = result.items().get(0);
    assertThat(item.name()).isEqualTo("맛있는 요리 교실");
    assertThat(item.category()).isEqualTo("교육>요리학원");
    assertThat(item.address()).isEqualTo("서울 강남구");
    assertThat(item.roadAddress()).isEqualTo("서울 강남구 테헤란로");
    assertThat(item.telephone()).isEqualTo("02-123-4567");
    assertThat(item.mapX()).isEqualTo("1");
    assertThat(item.mapY()).isEqualTo("2");
  }

  @Test
  void rejectsBlankOrMissingRegionBeforeCallingTheClient() {
    assertThatThrownBy(() -> service.search(1L, " ", "COOKING"))
        .isInstanceOf(ProjectException.class);
    assertThatThrownBy(() -> service.search(1L, null, "COOKING"))
        .isInstanceOf(ProjectException.class);
    verifyNoInteractions(client);
  }

  @Test
  void rejectsATooLongRegion() {
    String tooLong = "강".repeat(51);
    assertThatThrownBy(() -> service.search(1L, tooLong, "COOKING"))
        .isInstanceOf(ProjectException.class);
    verifyNoInteractions(client);
  }

  @Test
  void rejectsAnUnknownOrMissingHobbyKeyword() {
    assertThatThrownBy(() -> service.search(1L, "강남구", "UNKNOWN"))
        .isInstanceOf(ProjectException.class);
    assertThatThrownBy(() -> service.search(1L, "강남구", null)).isInstanceOf(ProjectException.class);
    verifyNoInteractions(client);
  }
}
