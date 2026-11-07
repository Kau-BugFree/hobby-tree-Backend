package com.hobbytree.hobbytree.domain.activity.service;

import com.hobbytree.hobbytree.domain.activity.code.ActivityErrorCode;
import com.hobbytree.hobbytree.domain.activity.dto.ActivityDetailResponse;
import com.hobbytree.hobbytree.domain.activity.entity.SavedActivityId;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityMaterialRepository;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRepository;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityStepRepository;
import com.hobbytree.hobbytree.domain.activity.repository.SavedActivityRepository;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.hobby.dto.ApiStageNumber;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActivityDetailService {
  private final UserRepository users;
  private final ActivityRepository activities;
  private final ActivityMaterialRepository materials;
  private final ActivityStepRepository steps;
  private final SavedActivityRepository saved;

  /** All catalog collections and the personal saved flag share one PostgreSQL snapshot. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ActivityDetailResponse detail(Long userId, Long activityId) {
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
    var activity =
        activities
            .findPublicDetail(activityId)
            .orElseThrow(() -> new ProjectException(ActivityErrorCode.NOT_FOUND));
    var stage = activity.getHobbyStage();
    var hobby = stage.getHobby();
    var materialItems =
        materials.findByActivity_ActivityIdOrderByActivityMaterialIdAsc(activityId).stream()
            .map(
                m ->
                    new ActivityDetailResponse.Material(
                        m.getActivityMaterialId(),
                        m.getMaterialName(),
                        m.getQuantityDescription(),
                        m.getIsRequired(),
                        m.getAcquisitionType()))
            .toList();
    var stepItems =
        steps.findByActivity_ActivityIdOrderByStepNumberAsc(activityId).stream()
            .map(
                s ->
                    new ActivityDetailResponse.Step(
                        s.getActivityStepId(),
                        s.getStepNumber(),
                        s.getTitle(),
                        s.getInstruction(),
                        s.getIsRequired(),
                        s.getImageUrl()))
            .toList();
    String notes = activity.getSafetyNotes();
    return new ActivityDetailResponse(
        activityId,
        activity.getActivityName(),
        activity.getThumbnailUrl() != null ? activity.getThumbnailUrl() : hobby.getThumbnailUrl(),
        activity.getExpectedTotalMinutes(),
        activity.getEstimatedCostMin(),
        activity.getDifficulty(),
        hobby.getIndoorOutdoor().name(),
        saved.existsById(new SavedActivityId(userId, activityId)),
        hobby.getHobbyId(),
        stage.getHobbyStageId(),
        ApiStageNumber.fromStored(stage.getStageNumber()),
        activity.getDetailedDescription(),
        activity.getContentVersion(),
        materialItems,
        stepItems,
        notes == null || notes.isBlank() ? List.of() : List.of(notes),
        activity.getContentUpdatedAt());
  }
}
