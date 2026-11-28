package com.hobbytree.hobbytree.domain.activity.service;

import com.hobbytree.hobbytree.domain.activity.code.ActivityRecordErrorCode;
import com.hobbytree.hobbytree.domain.activity.dto.StartActivityRequest;
import com.hobbytree.hobbytree.domain.activity.dto.StartActivityResponse;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordSnapshotRepository;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRepository;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.tree.code.TreeErrorCode;
import com.hobbytree.hobbytree.domain.tree.entity.ActivityRecord;
import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import com.hobbytree.hobbytree.domain.tree.repository.ActivityRecordRepository;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ActivityRecordStartService {
  private final UserRepository users;
  private final UserHobbyRepository trees;
  private final ActivityRecordRepository records;
  private final ActivityRepository activities;
  private final ActivityRecordSnapshotRepository snapshots;
  private final ActivityDetailService details;
  private final ObjectMapper mapper;
  private final Clock clock;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public StartActivityResponse start(Long userId, Long treeId, StartActivityRequest request) {
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
        trees
            .findOwnedForStatusChange(treeId, userId)
            .orElseThrow(() -> new ProjectException(TreeErrorCode.NOT_FOUND));
    if (tree.getTreeStatus() != TreeStatus.ACTIVE)
      throw new ProjectException(TreeErrorCode.NOT_ACTIVE);
    var current = records.findInProgressForStart(treeId);
    if (current.isPresent()) {
      var record = current.get();
      if (record.getRecordFormat() != 2)
        throw new ProjectException(ActivityRecordErrorCode.LEGACY_SNAPSHOT_REQUIRED);
      var progress =
          snapshots
              .progress(record.getActivityRecordId())
              .orElseThrow(
                  () -> new ProjectException(ActivityRecordErrorCode.LEGACY_SNAPSHOT_REQUIRED));
      if (!record.getActivity().getActivityId().equals(request.activityId()))
        throw new ProjectException(ActivityRecordErrorCode.OTHER_IN_PROGRESS);
      return response(record, progress, true);
    }
    if (!snapshots.lockCatalog(request.activityId()))
      throw new ProjectException(ActivityRecordErrorCode.NOT_STARTABLE);
    var activity =
        activities
            .findPublicDetail(request.activityId())
            .orElseThrow(() -> new ProjectException(ActivityRecordErrorCode.NOT_STARTABLE));
    if (!activity.getHobbyStage().getHobby().getHobbyId().equals(tree.getHobby().getHobbyId()))
      throw new ProjectException(ActivityRecordErrorCode.HOBBY_MISMATCH);
    if (activity.getHobbyStage().getStageNumber() > tree.getCurrentStageNumber())
      throw new ProjectException(ActivityRecordErrorCode.STAGE_LOCKED);
    var content = details.detail(userId, request.activityId());
    if (content.title().isBlank()
        || content.steps().isEmpty()
        || content.steps().stream()
            .anyMatch(
                step ->
                    step.title() == null
                        || step.title().isBlank()
                        || step.isRequired() == null
                        || step.description() == null
                        || step.description().isBlank())
        || content.steps().stream().noneMatch(step -> Boolean.TRUE.equals(step.isRequired())))
      throw new ProjectException(ActivityRecordErrorCode.NOT_STARTABLE);
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    var record = records.saveAndFlush(ActivityRecord.start(tree, activity, now));
    var snapshot = ActivityRecordStorageV1.snapshot(mapper, content);
    snapshots.capture(
        record.getActivityRecordId(), content, now, mapper.writeValueAsString(snapshot));
    return response(record, snapshots.progress(record.getActivityRecordId()).orElseThrow(), false);
  }

  private StartActivityResponse response(
      ActivityRecord record, ActivityRecordSnapshotRepository.Progress progress, boolean reused) {
    return new StartActivityResponse(
        record.getActivityRecordId(),
        record.getUserHobby().getUserHobbyId(),
        record.getActivity().getActivityId(),
        record.getActivityStatus().name(),
        progress.contentVersion(),
        progress.completedStepIds(),
        progress.percent(),
        record.getRecordVersion(),
        record.getStartedAt(),
        reused);
  }
}
