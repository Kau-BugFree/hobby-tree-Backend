package com.hobbytree.hobbytree.domain.activity.service;

import com.hobbytree.hobbytree.domain.activity.code.ActivityErrorCode;
import com.hobbytree.hobbytree.domain.activity.dto.SaveActivityResponse;
import com.hobbytree.hobbytree.domain.activity.dto.UnsaveActivityResponse;
import com.hobbytree.hobbytree.domain.activity.entity.*;
import com.hobbytree.hobbytree.domain.activity.repository.*;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SavedActivityService {
  private final UserRepository users;
  private final ActivityRepository activities;
  private final SavedActivityRepository savedActivities;

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

  private com.hobbytree.hobbytree.domain.user.entity.User lockActiveUser(Long userId) {
    var user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.SUSPENDED)
      throw new AuthException(AuthErrorCode.SUSPENDED);
    if (user.getAccountStatus() == AccountStatus.WITHDRAWN)
      throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);
    return user;
  }
}
