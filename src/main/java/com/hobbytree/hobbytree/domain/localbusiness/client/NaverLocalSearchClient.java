package com.hobbytree.hobbytree.domain.localbusiness.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hobbytree.hobbytree.domain.localbusiness.code.LocalBusinessErrorCode;
import com.hobbytree.hobbytree.domain.localbusiness.config.NaverLocalSearchProperties;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Calls NAVER API HUB's 지역 검색 결과 조회 (GET /search/v1/local). The one piece that talks to
// Naver, so it's the one piece tests mock instead of actually calling out - same pattern as
// KakaoCoordinateRegionResolver.
@Slf4j
@Component
public class NaverLocalSearchClient {
  private static final int DISPLAY_COUNT = 5;

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record NaverLocalItem(
      String title,
      String category,
      String description,
      String telephone,
      String address,
      String roadAddress,
      String mapx,
      String mapy) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record NaverLocalSearchResult(List<NaverLocalItem> items) {}

  private final RestClient naverLocalSearch;
  private final NaverLocalSearchProperties properties;

  public NaverLocalSearchClient(
      @Qualifier("naverLocalSearchRestClient") RestClient naverLocalSearch,
      NaverLocalSearchProperties properties) {
    this.naverLocalSearch = naverLocalSearch;
    this.properties = properties;
  }

  // query combines the caller's region text with the fixed hobby keyword (e.g. "강남구 요리") -
  // Naver's local search is keyword-based, not a true geo-radius search.
  public List<NaverLocalItem> search(String query) {
    NaverLocalSearchResult result;
    try {
      result =
          naverLocalSearch
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/search/v1/local")
                          .queryParam("query", query)
                          .queryParam("display", DISPLAY_COUNT)
                          .build())
              .header("X-NCP-APIGW-API-KEY-ID", properties.clientId())
              .header("X-NCP-APIGW-API-KEY", properties.clientSecret())
              .retrieve()
              .body(NaverLocalSearchResult.class);
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      // A 4xx other than 429 means our own request/credential is broken, not Naver being down -
      // same reasoning as KakaoCoordinateRegionResolver. Only the status and exception class go
      // to the log, never the exception message (it embeds the request URI/query).
      if (status != 429 && status / 100 == 4) {
        log.error("Naver local search request rejected: status={}", status);
        throw new ProjectException(LocalBusinessErrorCode.UPSTREAM_BAD_RESPONSE);
      }
      log.warn(
          "Naver local search unavailable: status={} exception={}",
          status,
          e.getClass().getSimpleName());
      throw new ProjectException(LocalBusinessErrorCode.UPSTREAM_UNAVAILABLE);
    } catch (RestClientException e) {
      log.warn("Naver local search unavailable: exception={}", e.getClass().getSimpleName());
      throw new ProjectException(LocalBusinessErrorCode.UPSTREAM_UNAVAILABLE);
    }

    if (result == null || result.items() == null)
      throw new ProjectException(LocalBusinessErrorCode.UPSTREAM_BAD_RESPONSE);
    return result.items().stream().map(NaverLocalSearchClient::stripTags).toList();
  }

  // Naver wraps the matched keyword in <b></b> inside title/description and HTML-escapes the
  // rest (e.g. "&amp;") - clean both before this ever reaches a client.
  private static NaverLocalItem stripTags(NaverLocalItem item) {
    return new NaverLocalItem(
        clean(item.title()),
        item.category(),
        clean(item.description()),
        item.telephone(),
        item.address(),
        item.roadAddress(),
        item.mapx(),
        item.mapy());
  }

  private static String clean(String text) {
    if (text == null) return null;
    return text.replaceAll("</?b>", "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'");
  }
}
