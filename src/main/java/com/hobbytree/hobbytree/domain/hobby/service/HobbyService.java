package com.hobbytree.hobbytree.domain.hobby.service;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityMaterialRepository;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRepository;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.hobby.code.HobbyErrorCode;
import com.hobbytree.hobbytree.domain.hobby.dto.ApiStageNumber;
import com.hobbytree.hobbytree.domain.hobby.dto.HobbyCategoryResponse;
import com.hobbytree.hobbytree.domain.hobby.dto.HobbyDetailResponse;
import com.hobbytree.hobbytree.domain.hobby.dto.HobbyGrowthRuleResponse;
import com.hobbytree.hobbytree.domain.hobby.dto.HobbyItemResponse;
import com.hobbytree.hobbytree.domain.hobby.dto.HobbyListResponse;
import com.hobbytree.hobbytree.domain.hobby.dto.HobbyStageListResponse;
import com.hobbytree.hobbytree.domain.hobby.dto.StarterMaterialResponse;
import com.hobbytree.hobbytree.domain.hobby.entity.Hobby;
import com.hobbytree.hobbytree.domain.hobby.entity.HobbyCategory;
import com.hobbytree.hobbytree.domain.hobby.entity.HobbyCategoryCatalog;
import com.hobbytree.hobbytree.domain.hobby.entity.IndoorOutdoor;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyRepository;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyStageRepository;
import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import com.hobbytree.hobbytree.domain.tree.entity.UserHobby;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HobbyService {
  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 50;
  private static final int MAX_KEYWORD_LENGTH = 50;
  // Cursor payload delimiter: a control character that can never appear in a hobby name/count,
  // unlike "_" which Korean names could plausibly contain.
  private static final String CURSOR_DELIMITER = "\u0001";

  private final UserRepository users;
  private final HobbyRepository hobbies;
  private final ActivityRepository activities;
  private final UserHobbyRepository userHobbies;
  private final HobbyStageRepository hobbyStages;
  private final ActivityMaterialRepository activityMaterials;

  /** Reads public catalog stages without requiring a planted tree or calculating personal locks. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public HobbyStageListResponse stages(Long userId, Long hobbyId) {
    if (userId == null) throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    var user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);
    var hobby =
        hobbies
            .findById(hobbyId)
            .filter(Hobby::getIsSupported)
            .orElseThrow(() -> new ProjectException(HobbyErrorCode.DETAIL_NOT_FOUND));
    int perStage = hobby.getActivitiesPerStage().intValue();
    var stages =
        hobbyStages.findStageSummaries(hobbyId).stream()
            .map(
                stage ->
                    new HobbyStageListResponse.Stage(
                        stage.getStageId(),
                        ApiStageNumber.fromStored(stage.getStageNumber()),
                        stage.getName(),
                        stage.getDescription(),
                        stage.getActivityCount(),
                        (long) stage.getStageNumber() * perStage))
            .toList();
    return new HobbyStageListResponse(hobby.getHobbyId(), hobby.getHobbyName(), stages);
  }

  // open-in-view is disabled in the ec2 profile, so without a transaction here the
  // Activity -> HobbyStage -> Hobby and UserHobby -> Hobby lazy proxies accessed below (after
  // each repository call has already returned) would fail to initialize once the session
  // closes (LazyInitializationException). The default profile's open-in-view masked this.
  @Transactional(readOnly = true)
  public HobbyListResponse list(
      Long userId,
      String keyword,
      Integer categoryId,
      Integer maxDurationMinutes,
      Integer maxCost,
      String indoorOutdoor,
      String sort,
      String cursor,
      Integer size) {
    var user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    // Separate from AuthFilter's shared status gate, which still admits PENDING_ONBOARDING.
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);

    String keywordPattern = parseKeywordPattern(keyword);
    HobbyCategory category = parseCategory(categoryId);
    IndoorOutdoor filterIndoorOutdoor = parseIndoorOutdoor(indoorOutdoor);
    validateRange(maxDurationMinutes, maxCost);
    boolean sortPopular = parseSortPopular(sort);
    int pageSize = parseSize(size);
    CursorPosition position = decodeCursor(cursor, sortPopular);

    List<Hobby> candidates =
        hobbies.findCandidates(
            keywordPattern != null,
            keywordPattern == null ? "" : keywordPattern,
            category != null,
            category,
            filterIndoorOutdoor != null,
            filterIndoorOutdoor);
    if (candidates.isEmpty()) return new HobbyListResponse(List.of(), null, false);

    List<Long> hobbyIds = candidates.stream().map(Hobby::getHobbyId).toList();

    Map<Long, Activity> introByHobbyId = new LinkedHashMap<>();
    for (Activity a : activities.findIntroActivityCandidates(hobbyIds)) {
      introByHobbyId.putIfAbsent(a.getHobbyStage().getHobby().getHobbyId(), a);
    }
    Map<Long, UserHobby> myHobbyByHobbyId = new LinkedHashMap<>();
    for (UserHobby uh : userHobbies.findByUser_UserIdAndHobby_HobbyIdIn(userId, hobbyIds)) {
      myHobbyByHobbyId.put(uh.getHobby().getHobbyId(), uh);
    }
    Map<Long, Long> plantedCountByHobbyId =
        sortPopular
            ? userHobbies.countPlantedByHobbyIds(hobbyIds).stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        UserHobbyRepository.HobbyPopularity::getHobbyId,
                        UserHobbyRepository.HobbyPopularity::getPlantedCount))
            : Map.of();

    List<Candidate> rows = new ArrayList<>();
    for (Hobby h : candidates) {
      Activity intro = introByHobbyId.get(h.getHobbyId());
      Integer introDuration = intro == null ? null : intro.getExpectedTotalMinutes();
      Integer introCost = intro == null ? null : intro.getEstimatedCostMin();
      // A hobby with no intro-activity data can't be evaluated against these filters, so it
      // is conservatively excluded rather than assumed to pass.
      if (maxDurationMinutes != null
          && (introDuration == null || introDuration > maxDurationMinutes)) continue;
      if (maxCost != null && (introCost == null || introCost > maxCost)) continue;
      rows.add(
          new Candidate(
              h,
              intro,
              myHobbyByHobbyId.get(h.getHobbyId()),
              plantedCountByHobbyId.getOrDefault(h.getHobbyId(), 0L)));
    }

    Comparator<Candidate> comparator =
        sortPopular
            ? Comparator.comparing(Candidate::plantedCount, Comparator.reverseOrder())
                .thenComparing(c -> c.hobby().getHobbyId())
            : Comparator.comparing((Candidate c) -> c.hobby().getHobbyName())
                .thenComparing(c -> c.hobby().getHobbyId());
    rows.sort(comparator);

    List<Candidate> afterCursor =
        position == null
            ? rows
            : rows.stream().filter(c -> isAfterCursor(c, position, sortPopular)).toList();

    boolean hasNext = afterCursor.size() > pageSize;
    List<Candidate> page = hasNext ? afterCursor.subList(0, pageSize) : afterCursor;
    String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1), sortPopular) : null;

    return new HobbyListResponse(page.stream().map(this::toItem).toList(), nextCursor, hasNext);
  }

  // Same lazy-initialization reasoning as list(): Activity -> HobbyStage -> Hobby is read
  // after findIntroActivityCandidates returns, and open-in-view is disabled under ec2.
  @Transactional(readOnly = true)
  public HobbyDetailResponse detail(Long userId, Long hobbyId) {
    var user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);

    Hobby hobby =
        hobbies
            .findById(hobbyId)
            .filter(Hobby::getIsSupported)
            .orElseThrow(() -> new ProjectException(HobbyErrorCode.DETAIL_NOT_FOUND));

    // Same "입문 활동" concept list() uses for minDurationMinutes/minCost/difficulty - its
    // materials are this hobby's starterMaterials.
    Activity intro =
        activities.findIntroActivityCandidates(List.of(hobbyId)).stream().findFirst().orElse(null);
    List<StarterMaterialResponse> starterMaterials =
        intro == null
            ? List.of()
            : activityMaterials
                .findByActivity_ActivityIdOrderByActivityMaterialIdAsc(intro.getActivityId())
                .stream()
                .map(m -> new StarterMaterialResponse(m.getMaterialName(), m.getIsRequired()))
                .toList();

    UserHobby mine =
        userHobbies.findByUser_UserIdAndHobby_HobbyIdIn(userId, List.of(hobbyId)).stream()
            .findFirst()
            .orElse(null);
    boolean planted =
        mine != null
            && (mine.getTreeStatus() == TreeStatus.ACTIVE
                || mine.getTreeStatus() == TreeStatus.PAUSED);

    return new HobbyDetailResponse(
        hobby.getHobbyId(),
        hobby.getHobbyName(),
        hobby.getDescription(),
        new HobbyCategoryResponse(
            HobbyCategoryCatalog.ID_BY_CATEGORY.get(hobby.getCategory()),
            HobbyCategoryCatalog.NAME.get(hobby.getCategory())),
        hobby.getThumbnailUrl(),
        intro == null ? null : intro.getExpectedTotalMinutes(),
        intro == null ? null : intro.getEstimatedCostMin(),
        intro == null ? null : intro.getDifficulty(),
        hobby.getIndoorOutdoor().name(),
        planted,
        planted ? mine.getUserHobbyId() : null,
        // No separate "longer intro text" column exists yet; reuses the same description
        // value (decided 2026-11-07) rather than adding a column for an MVP placeholder.
        hobby.getDescription(),
        (int) hobbyStages.countByHobby_HobbyId(hobbyId),
        new HobbyGrowthRuleResponse(hobby.getActivitiesPerStage()),
        starterMaterials,
        planted ? mine.getTreeStatus().name() : null,
        hobby.getUpdatedAt());
  }

  private boolean isAfterCursor(Candidate c, CursorPosition position, boolean sortPopular) {
    if (sortPopular) {
      long count = c.plantedCount();
      if (count != position.popularCount()) return count < position.popularCount();
      return c.hobby().getHobbyId() > position.hobbyId();
    }
    int cmp = c.hobby().getHobbyName().compareTo(position.name());
    if (cmp != 0) return cmp > 0;
    return c.hobby().getHobbyId() > position.hobbyId();
  }

  private HobbyItemResponse toItem(Candidate c) {
    Hobby h = c.hobby();
    UserHobby mine = c.mine();
    boolean planted =
        mine != null
            && (mine.getTreeStatus() == TreeStatus.ACTIVE
                || mine.getTreeStatus() == TreeStatus.PAUSED);
    return new HobbyItemResponse(
        h.getHobbyId(),
        h.getHobbyName(),
        h.getDescription(),
        new HobbyCategoryResponse(
            HobbyCategoryCatalog.ID_BY_CATEGORY.get(h.getCategory()),
            HobbyCategoryCatalog.NAME.get(h.getCategory())),
        h.getThumbnailUrl(),
        c.intro() == null ? null : c.intro().getExpectedTotalMinutes(),
        c.intro() == null ? null : c.intro().getEstimatedCostMin(),
        c.intro() == null ? null : c.intro().getDifficulty(),
        h.getIndoorOutdoor().name(),
        planted,
        planted ? mine.getUserHobbyId() : null);
  }

  private String parseKeywordPattern(String keyword) {
    if (keyword == null || keyword.isBlank()) return null;
    if (keyword.length() > MAX_KEYWORD_LENGTH)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    return "%" + keyword.toLowerCase() + "%";
  }

  private HobbyCategory parseCategory(Integer categoryId) {
    if (categoryId == null) return null;
    HobbyCategory category = HobbyCategoryCatalog.BY_ID.get(categoryId);
    if (category == null) throw new ProjectException(HobbyErrorCode.UNSUPPORTED_FILTER);
    return category;
  }

  private IndoorOutdoor parseIndoorOutdoor(String indoorOutdoor) {
    if (indoorOutdoor == null || indoorOutdoor.isBlank() || "ANY".equals(indoorOutdoor))
      return null;
    try {
      return IndoorOutdoor.valueOf(indoorOutdoor);
    } catch (IllegalArgumentException e) {
      throw new ProjectException(HobbyErrorCode.UNSUPPORTED_FILTER);
    }
  }

  private void validateRange(Integer maxDurationMinutes, Integer maxCost) {
    if (maxDurationMinutes != null && (maxDurationMinutes < 1 || maxDurationMinutes > 1440))
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    if (maxCost != null && maxCost < 0)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
  }

  private boolean parseSortPopular(String sort) {
    if (sort == null || sort.isBlank() || "NAME".equals(sort)) return false;
    if ("POPULAR".equals(sort)) return true;
    throw new ProjectException(HobbyErrorCode.UNSUPPORTED_FILTER);
  }

  private int parseSize(Integer size) {
    if (size == null) return DEFAULT_SIZE;
    if (size < 1 || size > MAX_SIZE)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    return size;
  }

  private record Candidate(Hobby hobby, Activity intro, UserHobby mine, long plantedCount) {}

  private record CursorPosition(String name, long popularCount, long hobbyId) {}

  // Opaque cursor: base64("{NAME|POPULAR}\u0001{sortValue}\u0001{hobbyId}"). The sort mode
  // travels inside the cursor so a client that changes `sort` mid-pagination gets a clean
  // HOBBY400_1 instead of a silently wrong page, instead of trusting the request's own `sort`.
  private CursorPosition decodeCursor(String cursor, boolean sortPopular) {
    if (cursor == null) return null;
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = decoded.split(CURSOR_DELIMITER, 3);
      boolean cursorIsPopular = "POPULAR".equals(parts[0]);
      if (cursorIsPopular != sortPopular)
        throw new ProjectException(HobbyErrorCode.UNSUPPORTED_FILTER);
      long hobbyId = Long.parseLong(parts[2]);
      return cursorIsPopular
          ? new CursorPosition(null, Long.parseLong(parts[1]), hobbyId)
          : new CursorPosition(parts[1], 0, hobbyId);
    } catch (ProjectException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ProjectException(HobbyErrorCode.UNSUPPORTED_FILTER);
    }
  }

  private String encodeCursor(Candidate last, boolean sortPopular) {
    String raw =
        sortPopular
            ? "POPULAR"
                + CURSOR_DELIMITER
                + last.plantedCount()
                + CURSOR_DELIMITER
                + last.hobby().getHobbyId()
            : "NAME"
                + CURSOR_DELIMITER
                + last.hobby().getHobbyName()
                + CURSOR_DELIMITER
                + last.hobby().getHobbyId();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }
}
