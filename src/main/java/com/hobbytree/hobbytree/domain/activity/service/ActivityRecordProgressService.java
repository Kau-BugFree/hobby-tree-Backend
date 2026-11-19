package com.hobbytree.hobbytree.domain.activity.service;

import com.hobbytree.hobbytree.domain.activity.code.ActivityRecordErrorCode;
import com.hobbytree.hobbytree.domain.activity.dto.SaveActivityProgressRequest;
import com.hobbytree.hobbytree.domain.activity.dto.SaveActivityProgressResponse;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordProgressRepository;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordSnapshotRepository;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.tree.code.TreeErrorCode;
import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.util.HashSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActivityRecordProgressService {
  private final UserRepository users;
  private final UserHobbyRepository trees;
  private final ActivityRecordProgressRepository records;
  private final ActivityRecordSnapshotRepository snapshots;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public SaveActivityProgressResponse save(
      Long userId, Long recordId, SaveActivityProgressRequest request) {
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
    long treeId =
        records
            .findOwnedTreeId(recordId, userId)
            .orElseThrow(() -> new ProjectException(ActivityRecordErrorCode.NOT_FOUND));
    var tree =
        trees
            .findOwnedForStatusChange(treeId, userId)
            .orElseThrow(() -> new ProjectException(ActivityRecordErrorCode.NOT_FOUND));
    if (tree.getTreeStatus() != TreeStatus.ACTIVE)
      throw new ProjectException(TreeErrorCode.NOT_ACTIVE);
    var record =
        records
            .lock(recordId, treeId)
            .orElseThrow(() -> new ProjectException(ActivityRecordErrorCode.NOT_FOUND));
    if (!"IN_PROGRESS".equals(record.status()))
      throw new ProjectException(ActivityRecordErrorCode.ALREADY_FINISHED);
    if (record.format() != 2)
      throw new ProjectException(ActivityRecordErrorCode.LEGACY_SNAPSHOT_REQUIRED);
    var snapshot =
        snapshots
            .snapshot(recordId)
            .orElseThrow(
                () -> new ProjectException(ActivityRecordErrorCode.LEGACY_SNAPSHOT_REQUIRED));
    if (!request.expectedVersion().equals(record.version()))
      throw new ProjectException(ActivityRecordErrorCode.VERSION_CONFLICT);
    var completed = new HashSet<>(request.completedStepIds());
    var allowed = snapshot.steps().stream().map(step -> step.stepId()).collect(Collectors.toSet());
    if (completed.size() != request.completedStepIds().size()
        || !allowed.containsAll(completed)
        || (request.elapsedSeconds() != null
            && (request.elapsedSeconds() < 0 || request.elapsedSeconds() > 86400)))
      throw new ProjectException(ActivityRecordErrorCode.INVALID_PROGRESS);
    var saved =
        records
            .advance(recordId, treeId, record.version(), request.elapsedSeconds())
            .orElseThrow(() -> new ProjectException(ActivityRecordErrorCode.VERSION_CONFLICT));
    records.replaceCompleted(recordId, request.completedStepIds());
    var progress = snapshots.progress(recordId).orElseThrow();
    return new SaveActivityProgressResponse(
        recordId,
        "IN_PROGRESS",
        progress.completedStepIds(),
        progress.percent(),
        saved.elapsedSeconds(),
        saved.version(),
        saved.updatedAt());
  }
}
