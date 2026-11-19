package com.hobbytree.hobbytree.domain.localbusiness;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.hobbytree.hobbytree.domain.localbusiness.client.NaverLocalSearchClient;
import com.hobbytree.hobbytree.domain.localbusiness.code.LocalBusinessErrorCode;
import com.hobbytree.hobbytree.domain.localbusiness.config.NaverLocalSearchProperties;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NaverLocalSearchClientTests {
  NaverLocalSearchProperties properties = new NaverLocalSearchProperties("test-id", "test-secret");

  NaverLocalSearchClient clientWithServer(MockRestServiceServer[] serverOut) {
    var builder = RestClient.builder().baseUrl("https://naverapihub.apigw.ntruss.com");
    serverOut[0] = MockRestServiceServer.bindTo(builder).build();
    return new NaverLocalSearchClient(builder.build(), properties);
  }

  @Test
  void parsesItemsAndStripsNaversBoldTagsAndHtmlEscapes() {
    var holder = new MockRestServiceServer[1];
    var client = clientWithServer(holder);
    holder[0]
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/v1/local")))
        .andExpect(header("X-NCP-APIGW-API-KEY-ID", "test-id"))
        .andExpect(header("X-NCP-APIGW-API-KEY", "test-secret"))
        .andRespond(
            withSuccess(
                """
                {"items":[{"title":"<b>강남</b>구 &amp; 요리교실","category":"교육>요리학원",
                "description":"<b>요리</b> 체험","telephone":"02-123-4567","address":"서울 강남구",
                "roadAddress":"서울 강남구 테헤란로","mapx":"111","mapy":"222"}]}
                """,
                MediaType.APPLICATION_JSON));

    var items = client.search("강남구 요리");

    assertThat(items).hasSize(1);
    var item = items.get(0);
    assertThat(item.title()).isEqualTo("강남구 & 요리교실");
    assertThat(item.description()).isEqualTo("요리 체험");
    assertThat(item.category()).isEqualTo("교육>요리학원");
    assertThat(item.telephone()).isEqualTo("02-123-4567");
    assertThat(item.address()).isEqualTo("서울 강남구");
    assertThat(item.roadAddress()).isEqualTo("서울 강남구 테헤란로");
    assertThat(item.mapx()).isEqualTo("111");
    assertThat(item.mapy()).isEqualTo("222");
    holder[0].verify();
  }

  @Test
  void rateLimitedResponseIsTreatedAsUnavailableNotABadRequest() {
    var holder = new MockRestServiceServer[1];
    var client = clientWithServer(holder);
    holder[0]
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/v1/local")))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

    assertThatThrownBy(() -> client.search("강남구 요리"))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(LocalBusinessErrorCode.UPSTREAM_UNAVAILABLE));
  }

  @Test
  void otherClientErrorsAreTreatedAsOurOwnBadRequestNotAnOutage() {
    var holder = new MockRestServiceServer[1];
    var client = clientWithServer(holder);
    holder[0]
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/v1/local")))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    assertThatThrownBy(() -> client.search("강남구 요리"))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(LocalBusinessErrorCode.UPSTREAM_BAD_RESPONSE));
  }

  @Test
  void upstreamServerErrorIsTreatedAsUnavailable() {
    var holder = new MockRestServiceServer[1];
    var client = clientWithServer(holder);
    holder[0]
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/v1/local")))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    assertThatThrownBy(() -> client.search("강남구 요리"))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(LocalBusinessErrorCode.UPSTREAM_UNAVAILABLE));
  }

  @Test
  void aResponseWithNoItemsFieldIsABadResponse() {
    var holder = new MockRestServiceServer[1];
    var client = clientWithServer(holder);
    holder[0]
        .expect(requestTo(org.hamcrest.Matchers.containsString("/search/v1/local")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.search("강남구 요리"))
        .isInstanceOfSatisfying(
            ProjectException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(LocalBusinessErrorCode.UPSTREAM_BAD_RESPONSE));
  }
}
