package com.hobbytree.hobbytree.domain.activity.service;

import com.hobbytree.hobbytree.domain.activity.code.ActivityErrorCode;
import com.hobbytree.hobbytree.domain.activity.dto.SaveActivityResponse;
import com.hobbytree.hobbytree.domain.activity.dto.SavedActivityListResponse;
import com.hobbytree.hobbytree.domain.activity.dto.UnsaveActivityResponse;
import com.hobbytree.hobbytree.domain.activity.entity.*;
import com.hobbytree.hobbytree.domain.activity.repository.*;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.hobby.dto.ApiStageNumber;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SavedActivityService {
  private final UserRepository users;
  private final ActivityRepository activities;
  private final SavedActivityRepository savedActivities;
  private final SavedActivityCursor cursors;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public SavedActivityListResponse listMine(
      Long userId, Long hobbyId, String cursor, Integer size) {
    if (userId == null) throw new AuthException(AuthErrorCode.ACCESS_TOKEN);
    requireActive(
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN)));

    int pageSize = size == null ? 20 : size;
    if (pageSize < 1 || pageSize > 50 || (hobbyId != null && hobbyId < 1)) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
    long hobbyFilter = hobbyId == null ? 0 : hobbyId;
    var before = cursors.decode(cursor, userId, hobbyFilter);
    var rows =
        savedActivities.findMyPage(
            userId,
            hobbyId != null,
            hobbyFilter,
            before != null,
            before == null ? Instant.EPOCH : before.savedAt(),
            before == null ? 0 : before.activityId(),
            PageRequest.of(0, pageSize + 1));
    boolean hasNext = rows.size() > pageSize;
    var page = hasNext ? rows.subList(0, pageSize) : rows;
    String next = null;
    if (hasNext) {
      var last = page.getLast();
      next =
          cursors.encode(
              userId,
              hobbyFilter,
              new SavedActivityCursor.Position(last.getSavedAt(), last.getActivityId()));
    }
    return new SavedActivityListResponse(page.stream().map(this::toItem).toList(), next, hasNext);
  }

  private SavedActivityListResponse.Item toItem(SavedActivityRepository.SavedItem row) {
    if (!row.getAvailable()) {
      return new SavedActivityListResponse.UnavailableItem(
          row.getActivityId(), row.getSavedAt(), false, "이용할 수 없는 활동입니다");
    }
    return new SavedActivityListResponse.AvailableItem(
        row.getActivityId(),
        row.getTitle(),
        row.getThumbnailUrl(),
        row.getDurationMinutes(),
        row.getEstimatedCost(),
        row.getDifficulty(),
        row.getIndoorOutdoor().name(),
        true,
        row.getHobbyId(),
        row.getHobbyName(),
        row.getStageId(),
        ApiStageNumber.fromStored(row.getStageNumber()),
        row.getSavedAt(),
        true);
  }

  @Transactional
  public SaveActivityResponse save(Long userId, Long activityId) {
    // Serialize same-user saves, including the first request with no saved row yet.
    var user = lockActiveUser(userId);
    // Prevent publication status from changing between validation and commit.
    var activity =
        activities
            .findForSaving(activityId)
            .filter(a -> a.getReviewStatus() == ReviewStatus.PUBLISHED)
            .orElseThrow(() -> new ProjectException(ActivityErrorCode.NOT_SAVABLE));
    var saved =
        savedActivities
            .findById(new SavedActivityId(userId, activityId))
            .orElseGet(() -> savedActivities.saveAndFlush(SavedActivity.of(user, activity)));
    return new SaveActivityResponse(activityId, true, saved.getSavedAt());
  }

  @Transactional
  public UnsaveActivityResponse unsave(Long userId, Long activityId) {
    lockActiveUser(userId);
    // No activity lookup: missing or unpublished activities must also be cancellable.
    savedActivities.deleteSaved(userId, activityId);
    return new UnsaveActivityResponse(activityId, false);
  }

  private User lockActiveUser(Long userId) {
    var user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    requireActive(user);
    return user;
  }

  private void requireActive(User user) {
    if (user.getAccountStatus() == AccountStatus.SUSPENDED)
      throw new AuthException(AuthErrorCode.SUSPENDED);
    if (user.getAccountStatus() == AccountStatus.WITHDRAWN)
      throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);
  }
}
