package com.hobbytree.hobbytree.domain.localbusiness.service;

import com.hobbytree.hobbytree.domain.hobby.entity.HobbyCategory;
import com.hobbytree.hobbytree.domain.localbusiness.client.NaverLocalSearchClient;
import com.hobbytree.hobbytree.domain.localbusiness.dto.LocalBusinessListResponse;
import com.hobbytree.hobbytree.domain.localbusiness.dto.LocalBusinessResponse;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import com.hobbytree.hobbytree.global.ratelimit.RateLimiter;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Issue #191: region is free-text, not resolved against the regions catalog - Naver's local
// search is keyword-based, not a true geo-radius search, so the region name is combined with a
// fixed Korean keyword straight into the query string (e.g. "강남구 요리교실"). The keyword names
// a class/academy, not the bare hobby word: "요리"/"악기"/"독서" alone mostly surface
// restaurants/instrument shops/bookstores rather than classes or meetups.
@Service
@RequiredArgsConstructor
public class LocalBusinessSearchService {
  private static final int MAX_REGION_LENGTH = 50;
  private static final String RATE_BUCKET = "local-business-search";
  private static final int RATE_LIMIT = 20;
  private static final Duration RATE_WINDOW = Duration.ofSeconds(60);
  private static final Map<HobbyCategory, String> KEYWORDS =
      Map.of(
          HobbyCategory.COOKING, "요리교실",
          HobbyCategory.INSTRUMENT, "악기학원",
          HobbyCategory.READING, "독서모임");

  private final NaverLocalSearchClient client;
  private final RateLimiter rateLimiter;

  public LocalBusinessListResponse search(Long userId, String region, String hobby) {
    rateLimiter.require(RATE_BUCKET, userId, RATE_LIMIT, RATE_WINDOW);
    String trimmedRegion = region == null ? "" : region.trim();
    if (trimmedRegion.isEmpty() || trimmedRegion.length() > MAX_REGION_LENGTH)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    HobbyCategory category = parseCategory(hobby);

    var items = client.search(trimmedRegion + " " + KEYWORDS.get(category));
    var responses =
        items.stream()
            .map(
                item ->
                    new LocalBusinessResponse(
                        item.title(),
                        item.category(),
                        item.address(),
                        item.roadAddress(),
                        item.telephone(),
                        item.mapx(),
                        item.mapy()))
            .toList();
    return new LocalBusinessListResponse(trimmedRegion, category.name(), responses);
  }

  private HobbyCategory parseCategory(String hobby) {
    try {
      return HobbyCategory.valueOf(hobby);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
  }
}
