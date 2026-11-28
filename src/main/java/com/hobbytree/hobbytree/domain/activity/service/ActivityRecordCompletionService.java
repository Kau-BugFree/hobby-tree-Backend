package com.hobbytree.hobbytree.domain.activity.service;

import com.hobbytree.hobbytree.domain.activity.code.ActivityRecordErrorCode;
import com.hobbytree.hobbytree.domain.activity.dto.ActivityRecordDetailResponse.Feedback;
import com.hobbytree.hobbytree.domain.activity.dto.CompleteActivityRequest;
import com.hobbytree.hobbytree.domain.activity.dto.CompleteActivityResponse;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityCompletionReceiptRepository;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordCompletionRepository;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordProgressRepository;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRecordSnapshotRepository;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.hobby.dto.ApiStageNumber;
import com.hobbytree.hobbytree.domain.tree.code.TreeErrorCode;
import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import com.hobbytree.hobbytree.domain.tree.entity.UserHobby;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class ActivityRecordCompletionService {
  private final UserRepository users;
  private final UserHobbyRepository trees;
  private final ActivityRecordProgressRepository ownership;
  private final ActivityRecordCompletionRepository records;
  private final ActivityRecordSnapshotRepository snapshots;
  private final ActivityCompletionReceiptRepository receipts;
  private final ObjectMapper mapper;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public CompleteActivityResponse complete(
      Long userId, Long recordId, CompleteActivityRequest request) {
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
        ownership
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
    ObjectNode payload = canonical(request);
    if (!"IN_PROGRESS".equals(record.status())) {
      var receipt =
          receipts
              .find(recordId)
              .orElseThrow(() -> new ProjectException(ActivityRecordErrorCode.ALREADY_FINISHED));
      if (!mapper.readTree(receipt.request()).equals(payload))
        throw new ProjectException(ActivityRecordErrorCode.ALREADY_FINISHED);
      return ActivityRecordStorageV1.readReceipt(
          mapper, receipt.response(), CompleteActivityResponse.class);
    }
    if (record.format() != 2)
      throw new ProjectException(ActivityRecordErrorCode.LEGACY_SNAPSHOT_REQUIRED);
    var snapshot =
        snapshots
            .snapshot(recordId)
            .orElseThrow(
                () -> new ProjectException(ActivityRecordErrorCode.LEGACY_SNAPSHOT_REQUIRED));
    if (!request.expectedVersion().equals(record.version()))
      throw new ProjectException(ActivityRecordErrorCode.VERSION_CONFLICT);
    boolean completed = "COMPLETED".equals(request.status());
    if (completed && snapshot.progress().percent() != 100)
      throw new ProjectException(ActivityRecordErrorCode.REQUIRED_STEPS_REMAIN);
    short previous = tree.getCurrentStageNumber();
    int perStage = completed ? records.lockGrowthRule(tree.getHobby().getHobbyId()) : 0;
    var before = records.growth(treeId, previous);
    if (completed
        && (records.hasMirrorMismatch(treeId)
            || perStage <= 0
            || previous != Math.min(UserHobby.MAX_STAGE_NUMBER, before.total() / perStage)
            || before.currentStage() != before.total() - (long) previous * perStage))
      throw new IllegalStateException("Growth ledger or stage baseline requires explicit repair");
    boolean applied = completed && !records.hasContribution(treeId, record.activityId());
    var finished =
        records
            .finish(recordId, treeId, record.version(), request, applied, previous)
            .orElseThrow(() -> new ProjectException(ActivityRecordErrorCode.VERSION_CONFLICT));
    if (applied)
      records.credit(recordId, treeId, record.activityId(), previous, finished.finishedAt());
    boolean stageChanged =
        applied && tree.advanceStageIfThresholdMet(before.currentStage() + 1, perStage);
    trees.flush();
    var feedback = request.feedback();
    var response =
        new CompleteActivityResponse(
            recordId,
            treeId,
            record.activityId(),
            request.status(),
            snapshot.progress().percent(),
            finished.version(),
            finished.finishedAt(),
            feedback == null
                ? null
                : new Feedback(
                    feedback.satisfactionScore(), feedback.perceivedDifficulty(), feedback.memo()),
            new CompleteActivityResponse.Growth(
                applied,
                before.total() + (applied ? 1 : 0),
                ApiStageNumber.fromStored(previous),
                ApiStageNumber.fromStored(tree.getCurrentStageNumber()),
                stageChanged,
                tree.getCurrentStageNumber() == UserHobby.MAX_STAGE_NUMBER));
    receipts.save(
        recordId,
        mapper.writeValueAsString(payload),
        ActivityRecordStorageV1.writeReceipt(mapper, response));
    return response;
  }

  private ObjectNode canonical(CompleteActivityRequest request) {
    boolean valid =
        request.status() != null
            && Set.of("COMPLETED", "PARTIAL", "NOT_DONE").contains(request.status());
    var feedback = request.feedback();
    if (!"NOT_DONE".equals(request.status()) && feedback == null) valid = false;
    if (feedback != null
        && (feedback.satisfactionScore() == null
            || feedback.satisfactionScore() < 1
            || feedback.satisfactionScore() > 5
            || feedback.perceivedDifficulty() == null
            || !Set.of("EASY", "APPROPRIATE", "HARD").contains(feedback.perceivedDifficulty())
            || !validText(feedback.memo(), 0, 1000))) valid = false;
    if ("NOT_DONE".equals(request.status()) && request.notDoneReason() == null) valid = false;
    if (!validText(request.notDoneReason(), 1, 200)
        || (request.notDoneReason() != null && request.notDoneReason().isBlank())) valid = false;
    if (!valid) throw new ProjectException(ActivityRecordErrorCode.INVALID_COMPLETION);
    var payload = mapper.createObjectNode();
    payload.put("status", request.status());
    payload.set("feedback", mapper.valueToTree(feedback));
    payload.put("notDoneReason", request.notDoneReason());
    return payload;
  }

  private boolean validText(String value, int min, int max) {
    if (value == null) return true;
    if (value.indexOf('\0') >= 0
        || value.codePointCount(0, value.length()) < min
        || value.codePointCount(0, value.length()) > max) return false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
      } else if (Character.isLowSurrogate(c)) return false;
    }
    return true;
  }
}
