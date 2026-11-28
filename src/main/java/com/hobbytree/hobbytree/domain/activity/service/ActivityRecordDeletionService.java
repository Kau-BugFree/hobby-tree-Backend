package com.hobbytree.hobbytree.domain.activity.service;

import com.hobbytree.hobbytree.domain.activity.code.ActivityRecordErrorCode;
import com.hobbytree.hobbytree.domain.activity.dto.DeleteActivityRecordResponse;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordCompletionRepository;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordDeletionRepository;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.hobby.dto.ApiStageNumber;
import com.hobbytree.hobbytree.domain.tree.entity.UserHobby;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ActivityRecordDeletionService {
  private final UserRepository users;
  private final UserHobbyRepository trees;
  private final ActivityRecordDeletionRepository records;
  private final ActivityRecordCompletionRepository growth;
  private final ObjectMapper mapper;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public DeleteActivityRecordResponse delete(Long userId, long recordId) {
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
            .ownedTreeIncludingDeleted(recordId, userId)
            .orElseThrow(() -> new ProjectException(ActivityRecordErrorCode.NOT_FOUND));
    var tree =
        trees
            .findOwnedForStatusChange(treeId, userId)
            .orElseThrow(() -> new ProjectException(ActivityRecordErrorCode.NOT_FOUND));
    var record =
        records
            .lock(recordId, treeId)
            .orElseThrow(() -> new ProjectException(ActivityRecordErrorCode.NOT_FOUND));
    if (record.deletedAt() != null) {
      String receipt =
          records
              .receipt(recordId)
              .orElseThrow(
                  () -> new ProjectException(ActivityRecordErrorCode.DELETION_INCONSISTENT));
      return ActivityRecordStorageV1.readReceipt(
          mapper, receipt, DeleteActivityRecordResponse.class);
    }
    if ("IN_PROGRESS".equals(record.status()))
      throw new ProjectException(ActivityRecordErrorCode.DELETE_IN_PROGRESS);
    short previous = tree.getCurrentStageNumber();
    int perStage = growth.lockGrowthRule(tree.getHobby().getHobbyId());
    var before = growth.growth(treeId, previous);
    if (perStage <= 0
        || growth.hasMirrorMismatch(treeId)
        || records.hasUnrepresentedCompletion(treeId)
        || previous != Math.min(UserHobby.MAX_STAGE_NUMBER, before.total() / perStage)
        || before.currentStage() != before.total() - (long) previous * perStage)
      throw new ProjectException(ActivityRecordErrorCode.DELETION_INCONSISTENT);
    String auditBefore = records.audit(treeId);
    boolean revoked = false;
    String action = "NONE";
    if ("COMPLETED".equals(record.status())) {
      var credit =
          records
              .lockCredit(treeId, record.activityId())
              .orElseThrow(
                  () -> new ProjectException(ActivityRecordErrorCode.DELETION_INCONSISTENT));
      var remaining = records.remainingCompletion(treeId, record.activityId(), recordId);
      if (remaining.isPresent()) {
        if (credit.origin() == recordId) {
          records.transfer(credit, remaining.get());
          action = "REASSIGN";
        }
      } else {
        if (credit.origin() != recordId)
          throw new ProjectException(ActivityRecordErrorCode.DELETION_INCONSISTENT);
        records.revoke(credit);
        records.redistribute(treeId, perStage);
        tree.recalculateStage(before.total() - 1, perStage);
        action = "REVOKE";
        revoked = true;
      }
    }
    trees.flush();
    var at = records.markDeleted(recordId);
    var response =
        new DeleteActivityRecordResponse(
            recordId,
            treeId,
            record.activityId(),
            true,
            at,
            new DeleteActivityRecordResponse.Growth(
                revoked,
                before.total() - (revoked ? 1 : 0),
                ApiStageNumber.fromStored(previous),
                ApiStageNumber.fromStored(tree.getCurrentStageNumber()),
                previous != tree.getCurrentStageNumber(),
                tree.getCurrentStageNumber() == UserHobby.MAX_STAGE_NUMBER));
    records.save(
        recordId,
        at,
        action,
        auditBefore,
        records.audit(treeId),
        ActivityRecordStorageV1.writeReceipt(mapper, response));
    return response;
  }
}
