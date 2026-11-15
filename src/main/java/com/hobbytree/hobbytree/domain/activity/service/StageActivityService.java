package com.hobbytree.hobbytree.domain.activity.service;

import com.hobbytree.hobbytree.domain.activity.code.StageErrorCode;
import com.hobbytree.hobbytree.domain.activity.dto.StageActivityListResponse;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRepository;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.hobby.dto.ApiStageNumber;
import com.hobbytree.hobbytree.domain.hobby.repository.HobbyStageRepository;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StageActivityService {
  private final UserRepository users;
  private final HobbyStageRepository stages;
  private final ActivityRepository activities;
  private final StageActivityCursor cursors;

  /** Previews public content without reading or modifying a user's growth/starting permissions. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public StageActivityListResponse list(Long userId, Long stageId, String cursor, String size) {
    if (userId == null) throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    var user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    switch (user.getAccountStatus()) {
      case ACTIVE -> {}
      case PENDING_ONBOARDING -> throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);
      case SUSPENDED -> throw new AuthException(AuthErrorCode.SUSPENDED);
      case WITHDRAWN -> throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
      default -> throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    }
    int pageSize = parseSize(size);
    long afterId = cursors.decode(cursor, userId, stageId);
    var stage =
        stages
            .findPublicWithHobby(stageId)
            .orElseThrow(() -> new ProjectException(StageErrorCode.NOT_FOUND));
    var hobby = stage.getHobby();
    var rows =
        activities.findPublishedStagePage(
            stageId, userId, afterId, PageRequest.of(0, pageSize + 1));
    boolean hasNext = rows.size() > pageSize;
    var page = hasNext ? rows.subList(0, pageSize) : rows;
    String next = hasNext ? cursors.encode(userId, stageId, page.getLast().getActivityId()) : null;
    return new StageActivityListResponse(
        stageId,
        hobby.getHobbyId(),
        ApiStageNumber.fromStored(stage.getStageNumber()),
        page.stream()
            .map(
                row ->
                    new StageActivityListResponse.Item(
                        row.getActivityId(),
                        row.getTitle(),
                        row.getThumbnailUrl() != null
                            ? row.getThumbnailUrl()
                            : hobby.getThumbnailUrl(),
                        row.getDurationMinutes(),
                        row.getEstimatedCost(),
                        row.getDifficulty(),
                        hobby.getIndoorOutdoor().name(),
                        row.getSaved()))
            .toList(),
        next,
        hasNext);
  }

  private int parseSize(String size) {
    if (size == null) return 20;
    try {
      int parsed = Integer.parseInt(size);
      if (parsed >= 1 && parsed <= 50) return parsed;
    } catch (NumberFormatException ignored) {
      // Convert query format/range failures to the existing common contract.
    }
    throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
  }
}
