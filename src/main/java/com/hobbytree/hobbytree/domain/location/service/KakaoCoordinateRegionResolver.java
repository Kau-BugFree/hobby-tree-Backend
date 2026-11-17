package com.hobbytree.hobbytree.domain.location.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hobbytree.hobbytree.domain.location.code.LocationErrorCode;
import com.hobbytree.hobbytree.domain.location.config.LocationProperties;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Converts device coordinates to our SIGUNGU-level regionId via Kakao's Local API
// (coord2regioncode), matched against the V8-seeded regions table (regionId == the 5-digit
// 법정동코드 prefix). A separate component (not inlined in the service) for the same reason
// SocialTokenVerifier is separate: it's the one piece that talks to an external provider, so
// it's the one piece tests mock instead of actually calling Kakao.
@Slf4j
@Component
public class KakaoCoordinateRegionResolver {
  public record KakaoRegionDocument(@JsonProperty("region_type") String regionType, String code) {}

  public record KakaoRegionCodeResponse(List<KakaoRegionDocument> documents) {}

  private final RestClient kakaoLocal;
  private final LocationProperties properties;

  public KakaoCoordinateRegionResolver(
      @Qualifier("kakaoLocalRestClient") RestClient kakaoLocal, LocationProperties properties) {
    this.kakaoLocal = kakaoLocal;
    this.properties = properties;
  }

  // Kakao returns both "H"(행정동) and "B"(법정동) documents for the same point; our regions
  // table is seeded from 법정동코드, so only the "B" document's code is usable here.
  public Long resolveSigunguRegionId(double latitude, double longitude) {
    KakaoRegionCodeResponse response;
    try {
      response =
          kakaoLocal
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/v2/local/geo/coord2regioncode.json")
                          .queryParam("x", longitude)
                          .queryParam("y", latitude)
                          .build())
              .header("Authorization", "KakaoAK " + properties.kakaoRestApiKey())
              .retrieve()
              .body(KakaoRegionCodeResponse.class);
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      // A 4xx other than 429 means our own request/credential is broken (most likely a
      // missing or invalid KAKAO_REST_API_KEY), not Kakao being unavailable - keep it out of
      // CONVERSION_UNAVAILABLE so operators can tell "fix our config" apart from "Kakao is
      // down" from logs alone. Only the status code and exception class go to the log: the
      // exception message embeds the request URI, which would leak the coordinates.
      if (status != 429 && status / 100 == 4) {
        log.error("Kakao coord2regioncode request rejected: status={}", status);
        throw new ProjectException(GeneralErrorCode.COMMON_INTERNAL_SERVER_ERROR);
      }
      log.warn(
          "Kakao coord2regioncode unavailable: status={} exception={}",
          status,
          e.getClass().getSimpleName());
      throw new ProjectException(LocationErrorCode.CONVERSION_UNAVAILABLE);
    } catch (RestClientException e) {
      // No HTTP status here - connect/read timeouts and other transport failures.
      log.warn("Kakao coord2regioncode unavailable: exception={}", e.getClass().getSimpleName());
      throw new ProjectException(LocationErrorCode.CONVERSION_UNAVAILABLE);
    }

    if (response == null || response.documents() == null)
      throw new ProjectException(LocationErrorCode.CONVERSION_UNAVAILABLE);

    List<KakaoRegionDocument> documents = response.documents();
    String legalDongCode =
        documents.stream()
            .filter(d -> "B".equals(d.regionType()))
            .map(KakaoRegionDocument::code)
            .findFirst()
            // An empty documents list means the coordinate has no matching region at all
            // (outside Korea, open ocean) - that's a legitimate "no region" result, not an
            // outage. A non-empty list missing the "B" document is a malformed/unexpected
            // response, which does stay a 503.
            .orElseThrow(
                () ->
                    documents.isEmpty()
                        ? new ProjectException(LocationErrorCode.REGION_NOT_FOUND)
                        : new ProjectException(LocationErrorCode.CONVERSION_UNAVAILABLE));

    try {
      return Long.parseLong(legalDongCode) / 100000;
    } catch (NumberFormatException e) {
      throw new ProjectException(LocationErrorCode.CONVERSION_UNAVAILABLE);
    }
  }
}
