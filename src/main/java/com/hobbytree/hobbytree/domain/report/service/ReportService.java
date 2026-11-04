package com.hobbytree.hobbytree.domain.report.service;

import com.hobbytree.hobbytree.domain.activity.entity.ReviewStatus;
import com.hobbytree.hobbytree.domain.activity.repository.ActivityRepository;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.report.code.ReportErrorCode;
import com.hobbytree.hobbytree.domain.report.dto.ReportRequest;
import com.hobbytree.hobbytree.domain.report.dto.ReportResponse;
import com.hobbytree.hobbytree.domain.report.entity.Report;
import com.hobbytree.hobbytree.domain.report.entity.ReportReason;
import com.hobbytree.hobbytree.domain.report.entity.ReportTargetType;
import com.hobbytree.hobbytree.domain.report.repository.ReportRepository;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
  private static final int DESCRIPTION_MIN_LENGTH = 10;
  private static final int DESCRIPTION_MAX_LENGTH = 1000;
  private static final Duration DUPLICATE_WINDOW = Duration.ofHours(24);

  private final UserRepository users;
  private final ActivityRepository activities;
  private final ReportRepository reports;
  private final Clock clock;

  public ReportService(
      UserRepository users, ActivityRepository activities, ReportRepository reports, Clock clock) {
    this.users = users;
    this.activities = activities;
    this.reports = reports;
    this.clock = clock;
  }

  @Transactional
  public ReportResponse report(Long userId, ReportRequest request) {
    // Lock the user row so two concurrent identical reports from the same user serialize
    // instead of both passing the 24h duplicate check and inserting twice.
    User user =
        users.findForLogin(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.SUSPENDED)
      throw new AuthException(AuthErrorCode.SUSPENDED);
    if (user.getAccountStatus() == AccountStatus.WITHDRAWN)
      throw new AuthException(AuthErrorCode.WITHDRAWN_ACCESS);
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);

    ReportTargetType targetType = parseTargetType(request.targetType());
    ReportReason reason = parseReason(request.reason());
    String description = validateDescription(request.description());
    assertTargetExistsAndAccessible(targetType, request.targetId());

    Instant since = clock.instant().minus(DUPLICATE_WINDOW);
    if (reports.existsByReporterUser_UserIdAndTargetTypeAndTargetIdAndReasonAndCreatedAtAfter(
        userId, targetType, request.targetId(), reason, since)) {
      throw new ProjectException(ReportErrorCode.DUPLICATE);
    }

    var saved = reports.save(Report.of(user, targetType, request.targetId(), reason, description));
    return new ReportResponse(
        saved.getReportId(),
        targetType.name(),
        saved.getTargetId(),
        saved.getStatus().name(),
        saved.getCreatedAt());
  }

  private ReportTargetType parseTargetType(String value) {
    try {
      return ReportTargetType.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw new ProjectException(ReportErrorCode.UNSUPPORTED_REQUEST);
    }
  }

  private ReportReason parseReason(String value) {
    try {
      return ReportReason.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw new ProjectException(ReportErrorCode.UNSUPPORTED_REQUEST);
    }
  }

  private String validateDescription(String raw) {
    String trimmed = raw.strip();
    // Count code points, not UTF-16 units: V4's length(btrim(description)) CHECK counts
    // characters, so a 5-emoji description (10 UTF-16 units, 5 code points) must agree with
    // the DB's count of 5, not silently pass here and then fail the DB constraint instead.
    int length = trimmed.codePointCount(0, trimmed.length());
    if (length < DESCRIPTION_MIN_LENGTH || length > DESCRIPTION_MAX_LENGTH) {
      throw new ProjectException(ReportErrorCode.UNSUPPORTED_REQUEST);
    }
    return trimmed;
  }

  private void assertTargetExistsAndAccessible(ReportTargetType targetType, Long targetId) {
    boolean accessible =
        switch (targetType) {
          case ACTIVITY ->
              activities
                  .findById(targetId)
                  .filter(a -> a.getReviewStatus() == ReviewStatus.PUBLISHED)
                  .isPresent();
          // API-49 (sponsored content) isn't implemented yet, so no such target can exist.
          case SPONSORED_CONTENT -> false;
        };
    if (!accessible) throw new ProjectException(ReportErrorCode.TARGET_NOT_FOUND);
  }
}
