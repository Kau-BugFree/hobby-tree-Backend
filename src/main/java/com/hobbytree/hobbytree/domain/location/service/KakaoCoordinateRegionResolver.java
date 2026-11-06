package com.hobbytree.hobbytree.domain.location.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hobbytree.hobbytree.domain.location.code.LocationErrorCode;
import com.hobbytree.hobbytree.domain.location.config.LocationProperties;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// Converts device coordinates to our SIGUNGU-level regionId via Kakao's Local API
// (coord2regioncode), matched against the V8-seeded regions table (regionId == the 5-digit
// 법정동코드 prefix). A separate component (not inlined in the service) for the same reason
// SocialTokenVerifier is separate: it's the one piece that talks to an external provider, so
// it's the one piece tests mock instead of actually calling Kakao.
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
    try {
      KakaoRegionCodeResponse response =
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
      if (response == null || response.documents() == null)
        throw new ProjectException(LocationErrorCode.CONVERSION_UNAVAILABLE);
      String legalDongCode =
          response.documents().stream()
              .filter(d -> "B".equals(d.regionType()))
              .map(KakaoRegionDocument::code)
              .findFirst()
              .orElseThrow(() -> new ProjectException(LocationErrorCode.CONVERSION_UNAVAILABLE));
      return Long.parseLong(legalDongCode) / 100000;
    } catch (RestClientException | NumberFormatException e) {
      throw new ProjectException(LocationErrorCode.CONVERSION_UNAVAILABLE);
    }
  }
}
