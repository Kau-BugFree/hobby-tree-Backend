package com.hobbytree.hobbytree.domain.region.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.region.code.RegionErrorCode;
import com.hobbytree.hobbytree.domain.region.dto.RegionItemResponse;
import com.hobbytree.hobbytree.domain.region.dto.RegionListResponse;
import com.hobbytree.hobbytree.domain.region.entity.Region;
import com.hobbytree.hobbytree.domain.region.entity.RegionLevel;
import com.hobbytree.hobbytree.domain.region.repository.RegionRepository;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RegionService {
  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 50;
  private static final int MAX_KEYWORD_LENGTH = 50;
  // A control character, not "_": region names are plain Korean place names and never contain
  // it, unlike "_" which is at least conceivable in a name.
  private static final String CURSOR_DELIMITER = "\u0001";

  private final UserRepository users;
  private final RegionRepository regions;

  public RegionListResponse search(
      Long userId, String keyword, String level, Long parentRegionId, String cursor, Integer size) {
    var user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    // Separate from AuthFilter's shared status gate, which still admits PENDING_ONBOARDING.
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);

    String keywordPattern = parseKeywordPattern(keyword);
    RegionLevel regionLevel = parseLevel(level);
    int pageSize = parseSize(size);
    CursorPosition position = decodeCursor(cursor);

    List<Region> rows =
        regions.findPage(
            regionLevel,
            keywordPattern,
            parentRegionId != null,
            parentRegionId == null ? 0L : parentRegionId,
            position != null,
            position == null ? "" : position.name(),
            position == null ? 0L : position.regionId(),
            PageRequest.of(0, pageSize + 1));

    boolean hasNext = rows.size() > pageSize;
    List<Region> page = hasNext ? rows.subList(0, pageSize) : rows;
    String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1)) : null;

    return new RegionListResponse(page.stream().map(this::toItem).toList(), nextCursor, hasNext);
  }

  private RegionItemResponse toItem(Region r) {
    return new RegionItemResponse(
        r.getRegionId(), r.getName(), r.getFullName(), r.getLevel().name(), r.getParentRegionId());
  }

  // "검색어...가 올바르지 않음" (LOCATION400_1) covers both the trim-then-empty and
  // over-50-chars cases; only a fully-missing `keyword` param reaches COMMON400_1, thrown by
  // Spring's own required-parameter binding before this method ever runs.
  private String parseKeywordPattern(String keyword) {
    String trimmed = keyword == null ? "" : keyword.trim();
    if (trimmed.isEmpty() || trimmed.length() > MAX_KEYWORD_LENGTH)
      throw new ProjectException(RegionErrorCode.INVALID_SEARCH);
    // Escape the LIKE metacharacters themselves before adding our own wildcards: a keyword of
    // literally "%" or "_" must be searched for as that literal character, not treated as a
    // wildcard that matches every row. RegionRepository's query pairs this with ESCAPE '\'.
    String escaped =
        trimmed.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  private RegionLevel parseLevel(String level) {
    if (level == null || level.isBlank() || "SIGUNGU".equals(level)) return RegionLevel.SIGUNGU;
    if ("DONG".equals(level)) return RegionLevel.DONG;
    // SIDO rows exist only as parent anchors for SIGUNGU rows, never as a searchable level.
    throw new ProjectException(RegionErrorCode.INVALID_SEARCH);
  }

  private int parseSize(Integer size) {
    if (size == null) return DEFAULT_SIZE;
    if (size < 1 || size > MAX_SIZE)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    return size;
  }

  private record CursorPosition(String name, long regionId) {}

  // Opaque cursor: base64("{name}\u0001{regionId}").
  private CursorPosition decodeCursor(String cursor) {
    if (cursor == null) return null;
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = decoded.split(CURSOR_DELIMITER, 2);
      return new CursorPosition(parts[0], Long.parseLong(parts[1]));
    } catch (RuntimeException e) {
      throw new ProjectException(RegionErrorCode.INVALID_SEARCH);
    }
  }

  private String encodeCursor(Region last) {
    String raw = last.getName() + CURSOR_DELIMITER + last.getRegionId();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }
}
