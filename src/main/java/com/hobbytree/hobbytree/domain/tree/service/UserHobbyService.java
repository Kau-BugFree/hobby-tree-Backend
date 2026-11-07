package com.hobbytree.hobbytree.domain.tree.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyStageRepository;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyStageRepository.StageName;
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

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public UserHobbyListResponse list(Long userId, String status, String cursor, String size) {
    if (userId == null) throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    var user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);

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
    Map<StageKey, String> stageNames =
        stages.findStageNamesByHobbyIds(hobbyIds).stream()
            .collect(
                Collectors.toMap(
                    stage -> new StageKey(stage.getHobbyId(), stage.getStageNumber()),
                    StageName::getStageName));
    var treeIds = trees.stream().map(UserHobby::getUserHobbyId).toList();
    Map<Long, GrowthCounts> growthByTree =
        records.countGrowthByTreeIds(treeIds).stream()
            .collect(Collectors.toMap(GrowthCounts::getUserHobbyId, Function.identity()));

    return trees.stream()
        .map(tree -> toItem(tree, stageNames, growthByTree.get(tree.getUserHobbyId())))
        .toList();
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
