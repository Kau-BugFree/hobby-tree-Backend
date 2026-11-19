package com.hobbytree.hobbytree.domain.activity.service;

import com.hobbytree.hobbytree.domain.activity.code.ActivityRecordErrorCode;
import com.hobbytree.hobbytree.domain.activity.dto.ActivityRecordDetailResponse;
import com.hobbytree.hobbytree.domain.activity.dto.ActivityRecordDetailResponse.Feedback;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordDetailRepository;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordSnapshotRepository;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActivityRecordDetailService {
  private final UserRepository users;
  private final ActivityRecordDetailRepository records;
  private final ActivityRecordSnapshotRepository snapshots;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ActivityRecordDetailResponse detail(Long userId, Long recordId) {
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
    var record =
        records
            .findOwned(recordId, userId)
            .orElseThrow(() -> new ProjectException(ActivityRecordErrorCode.NOT_FOUND));
    if (record.format() != 2)
      throw new ProjectException(ActivityRecordErrorCode.LEGACY_SNAPSHOT_REQUIRED);
    var snapshot =
        snapshots
            .snapshot(recordId)
            .orElseThrow(
                () -> new ProjectException(ActivityRecordErrorCode.LEGACY_SNAPSHOT_REQUIRED));
    var progress = snapshot.progress();
    boolean inProgress = "IN_PROGRESS".equals(record.status());
    Feedback feedback =
        !inProgress
                && (record.satisfaction() != null
                    || record.difficulty() != null
                    || record.memo() != null)
            ? new Feedback(record.satisfaction(), record.difficulty(), record.memo())
            : null;
    return new ActivityRecordDetailResponse(
        record.recordId(),
        record.treeId(),
        record.activityId(),
        snapshot.title(),
        record.status(),
        record.treeStatus(),
        progress.contentVersion(),
        snapshot.steps(),
        progress.completedStepIds(),
        progress.percent(),
        record.elapsedSeconds(),
        record.version(),
        inProgress && "ACTIVE".equals(record.treeStatus()),
        record.startedAt(),
        record.finishedAt(),
        feedback);
  }
}
