package com.hobbytree.hobbytree.domain.tree.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.hobby.code.HobbyErrorCode;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyRepository;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyStageRepository;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyStageRepository.StageName;
import com.hobbytree.hobbytree.domain.recommendation.code.RecommendationErrorCode;
import com.hobbytree.hobbytree.domain.recommendation.repository.RecommendationItemRepository;
import com.hobbytree.hobbytree.domain.recommendation.repository.RecommendationRepository;
import com.hobbytree.hobbytree.domain.tree.code.TreeErrorCode;
import com.hobbytree.hobbytree.domain.tree.dto.ChangeUserHobbyStatusRequest;
import com.hobbytree.hobbytree.domain.tree.dto.ChangeUserHobbyStatusResponse;
import com.hobbytree.hobbytree.domain.tree.dto.PlantUserHobbyRequest;
import com.hobbytree.hobbytree.domain.tree.dto.PlantUserHobbyResponse;
import com.hobbytree.hobbytree.domain.tree.dto.UserHobbyDetailResponse;
import com.hobbytree.hobbytree.domain.tree.dto.UserHobbyDetailResponse.CurrentActivityRecord;
import com.hobbytree.hobbytree.domain.tree.dto.UserHobbyListResponse;
import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import com.hobbytree.hobbytree.domain.tree.entity.UserHobby;
import com.hobbytree.hobbytree.domain.tree.repository.ActivityRecordRepository;
import com.hobbytree.hobbytree.domain.tree.repository.ActivityRecordRepository.GrowthCounts;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserHobbyService {
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 50;
  private static final int MAX_TREE_COUNT = 5;
  // V1 defines stage 5 as terminal, independent of which catalog rows have been seeded.
  private static final int MAX_STAGE_NUMBER = 5;
  private static final List<TreeStatus> OCCUPYING_STATUSES =
      List.of(TreeStatus.ACTIVE, TreeStatus.PAUSED);

  private final UserHobbyRepository userHobbies;
  private final UserRepository users;
  private final HobbyStageRepository stages;
  private final ActivityRecordRepository records;
  private final HobbyRepository hobbies;
  private final RecommendationRepository recommendations;
  private final RecommendationItemRepository recommendationItems;

  /** Serializes slot changes with planting, then rechecks the committed account and tree. */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ChangeUserHobbyStatusResponse changeStatus(
      Long userId, Long userHobbyId, ChangeUserHobbyStatusRequest request) {
    if (userId == null) throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    var user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    switch (user.getAccountStatus()) {
      case ACTIVE -> {}
      case PENDING_ONBOARDING -> throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);
      case SUSPENDED -> throw new AuthException(AuthErrorCode.SUSPENDED);
      case WITHDRAWN -> throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
      default -> throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    }
    var tree =
        userHobbies
            .findOwnedForStatusChange(userHobbyId, userId)
            .orElseThrow(() -> new ProjectException(TreeErrorCode.NOT_FOUND));
    var requested = parseStatus(request.status());
    if (requested == null) throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    if (tree.getTreeStatus() != requested) {
      if (tree.getTreeStatus() == TreeStatus.ENDED) {
        if (requested == TreeStatus.PAUSED)
          throw new ProjectException(TreeErrorCode.INVALID_STATUS_TRANSITION);
        if (userHobbies.countByUser_UserIdAndTreeStatusIn(userId, OCCUPYING_STATUSES)
            >= MAX_TREE_COUNT) throw new ProjectException(TreeErrorCode.NO_AVAILABLE_SLOT);
      }
      // PostgreSQL TIMESTAMPTZ precision is microseconds; return the same stored event time.
      tree.changeStatus(requested, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
      userHobbies.flush();
    }
    // Hibernate reads V13's DB-generated value after flush. No-op requests keep the stored value.
    return new ChangeUserHobbyStatusResponse(
        tree.getUserHobbyId(),
        tree.getTreeStatus(),
        tree.getCurrentStageNumber(),
        tree.getPausedAt(),
        tree.getEndedAt(),
        tree.getUpdatedAt());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PlantUserHobbyResponse plant(Long userId, PlantUserHobbyRequest request) {
    if (userId == null) throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    // Recheck account status after waiting for the same lock used by withdrawal and profile
    // updates.
    var user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    switch (user.getAccountStatus()) {
      case ACTIVE -> {}
      case PENDING_ONBOARDING -> throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);
      case SUSPENDED -> throw new AuthException(AuthErrorCode.SUSPENDED);
      case WITHDRAWN -> throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
      default -> throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    }

    var hobby =
        hobbies
            .findForPlanting(request.hobbyId())
            .orElseThrow(() -> new ProjectException(HobbyErrorCode.NOT_PLANTABLE));
    if (userHobbies.existsByUser_UserIdAndHobby_HobbyId(userId, request.hobbyId()))
      throw new ProjectException(TreeErrorCode.ALREADY_PLANTED);
    if (userHobbies.countByUser_UserIdAndTreeStatusIn(userId, OCCUPYING_STATUSES) >= MAX_TREE_COUNT)
      throw new ProjectException(TreeErrorCode.NO_AVAILABLE_SLOT);
    if (request.recommendationId() != null
        && (!recommendations.existsByRecommendationIdAndUser_UserId(
                request.recommendationId(), userId)
            || !recommendationItems.existsByRecommendation_RecommendationIdAndHobby_HobbyId(
                request.recommendationId(), request.hobbyId())))
      throw new ProjectException(RecommendationErrorCode.NOT_RECOMMENDED);

    UserHobby tree;
    try {
      tree = userHobbies.saveAndFlush(UserHobby.plant(user, hobby));
    } catch (DataIntegrityViolationException exception) {
      // Only the existing tree-per-hobby UNIQUE constraint represents a duplicate planting.
      // Throwing out of this transaction lets Spring roll back the failed flush.
      for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
        if (cause instanceof ConstraintViolationException violation
            && "23505".equals(violation.getSQLState())
            && "user_hobbies_user_id_hobby_id_key".equals(violation.getConstraintName()))
          throw new ProjectException(TreeErrorCode.ALREADY_PLANTED);
      }
      throw exception;
    }
    return new PlantUserHobbyResponse(
        tree.getUserHobbyId(),
        hobby.getHobbyId(),
        hobby.getHobbyName(),
        tree.getTreeStatus(),
        tree.getCurrentStageNumber(),
        0,
        tree.getPlantedAt());
  }

  private void requireOnboardedUser(Long userId) {
    if (userId == null) throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    var user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public UserHobbyDetailResponse detail(Long userId, Long userHobbyId) {
    requireOnboardedUser(userId);
    var tree =
        userHobbies
            .findOwnedByIdWithHobby(userHobbyId, userId)
            .orElseThrow(() -> new ProjectException(TreeErrorCode.NOT_FOUND));
    var definitions = stages.findStageNamesByHobbyIds(List.of(tree.getHobby().getHobbyId()));
    var growth =
        records.countGrowthByTreeIds(List.of(userHobbyId)).stream().findFirst().orElse(null);
    // Use the same mapping and growth calculation as API-18.
    var item = toItem(tree, stageNames(definitions), growth);
    var current =
        records.findCurrentActivity(userHobbyId, PageRequest.of(0, 1)).stream()
            .map(
                record ->
                    new CurrentActivityRecord(
                        record.getActivityRecordId(),
                        record.getActivity().getActivityId(),
                        record.getActivity().getActivityName(),
                        // The required-step snapshot and step_progress schema are not implemented
                        // yet.
                        null))
            .findFirst()
            .orElse(null);
    return new UserHobbyDetailResponse(
        item.userHobbyId(),
        item.hobbyId(),
        item.hobbyName(),
        item.thumbnailUrl(),
        item.treeStatus(),
        item.currentStageNumber(),
        item.currentStageName(),
        item.distinctCompletedActivityCount(),
        item.requiredCountForNextStage(),
        item.remainingCountForNextStage(),
        item.plantedAt(),
        MAX_STAGE_NUMBER + 1,
        tree.getCurrentStageNumber() == MAX_STAGE_NUMBER,
        growth == null ? 0 : growth.getCurrentStageCount(),
        item.requiredCountForNextStage(),
        current,
        records.findLastActivityAt(userHobbyId).orElse(null));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public UserHobbyListResponse list(Long userId, String status, String cursor, String size) {
    requireOnboardedUser(userId);

    TreeStatus treeStatus = parseStatus(status);
    int pageSize = parseSize(size);
    Cursor position = decodeCursor(cursor);
    var rows =
        userHobbies.findOwnedWithHobby(
            userId,
            treeStatus != null,
            treeStatus == null ? TreeStatus.ACTIVE : treeStatus,
            position != null,
            position == null ? Instant.EPOCH : position.plantedAt(),
            position == null ? 0 : position.id(),
            PageRequest.of(0, pageSize + 1));

    boolean hasNext = rows.size() > pageSize;
    var page = hasNext ? rows.subList(0, pageSize) : rows;
    String nextCursor = hasNext ? encodeCursor(page.getLast()) : null;
    long occupiedCount = userHobbies.countByUser_UserIdAndTreeStatusIn(userId, OCCUPYING_STATUSES);
    return new UserHobbyListResponse(
        toItems(page), nextCursor, hasNext, occupiedCount, MAX_TREE_COUNT);
  }

  private List<UserHobbyListResponse.Item> toItems(List<UserHobby> trees) {
    if (trees.isEmpty()) return List.of();

    var hobbyIds = trees.stream().map(tree -> tree.getHobby().getHobbyId()).toList();
    Map<StageKey, String> stageNames = stageNames(stages.findStageNamesByHobbyIds(hobbyIds));
    var treeIds = trees.stream().map(UserHobby::getUserHobbyId).toList();
    Map<Long, GrowthCounts> growthByTree =
        records.countGrowthByTreeIds(treeIds).stream()
            .collect(Collectors.toMap(GrowthCounts::getUserHobbyId, Function.identity()));

    return trees.stream()
        .map(tree -> toItem(tree, stageNames, growthByTree.get(tree.getUserHobbyId())))
        .toList();
  }

  private Map<StageKey, String> stageNames(List<StageName> definitions) {
    return definitions.stream()
        .collect(
            Collectors.toMap(
                stage -> new StageKey(stage.getHobbyId(), stage.getStageNumber()),
                StageName::getStageName));
  }

  private UserHobbyListResponse.Item toItem(
      UserHobby tree, Map<StageKey, String> stageNames, GrowthCounts growth) {
    var hobby = tree.getHobby();
    short stageNumber = tree.getCurrentStageNumber();
    Integer required =
        stageNumber == MAX_STAGE_NUMBER ? null : hobby.getActivitiesPerStage().intValue();
    // The total spans all credited stages. Only credit earned at the current stage
    // reduces the next-stage requirement. V1 guarantees one record per tree and activity.
    long total = growth == null ? 0 : growth.getTotalCount();
    long atCurrentStage = growth == null ? 0 : growth.getCurrentStageCount();
    long remaining = required == null ? 0 : Math.max(0, required - atCurrentStage);
    return new UserHobbyListResponse.Item(
        tree.getUserHobbyId(),
        hobby.getHobbyId(),
        hobby.getHobbyName(),
        hobby.getThumbnailUrl(),
        tree.getTreeStatus(),
        stageNumber,
        stageNames.get(new StageKey(hobby.getHobbyId(), stageNumber)),
        total,
        required,
        remaining,
        tree.getPlantedAt());
  }

  private TreeStatus parseStatus(String status) {
    if (status == null) return null;
    try {
      return TreeStatus.valueOf(status);
    } catch (IllegalArgumentException e) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
  }

  private int parseSize(String size) {
    if (size == null) return DEFAULT_PAGE_SIZE;
    int parsed;
    try {
      parsed = Integer.parseInt(size);
    } catch (NumberFormatException e) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
    if (parsed < 1 || parsed > MAX_PAGE_SIZE)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    return parsed;
  }

  private Cursor decodeCursor(String cursor) {
    if (cursor == null) return null;
    if (cursor.length() > 128) throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = decoded.split("_", 2);
      if (parts.length != 2) throw new IllegalArgumentException();
      var position = new Cursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
      if (position.id() < 1 || !cursor.equals(encodeCursor(position)))
        throw new IllegalArgumentException();
      return position;
    } catch (DateTimeException | IllegalArgumentException e) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
  }

  private String encodeCursor(UserHobby tree) {
    return encodeCursor(new Cursor(tree.getPlantedAt(), tree.getUserHobbyId()));
  }

  private String encodeCursor(Cursor position) {
    String raw = position.plantedAt() + "_" + position.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private record Cursor(Instant plantedAt, long id) {}

  private record StageKey(Long hobbyId, short stageNumber) {}
}
