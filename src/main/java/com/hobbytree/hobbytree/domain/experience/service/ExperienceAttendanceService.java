package com.hobbytree.hobbytree.domain.experience.service;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.experience.code.AttendanceErrorCode;
import com.hobbytree.hobbytree.domain.experience.code.ReservationErrorCode;
import com.hobbytree.hobbytree.domain.experience.dto.AttendanceGrowthResponse;
import com.hobbytree.hobbytree.domain.experience.dto.ConfirmAttendanceRequest;
import com.hobbytree.hobbytree.domain.experience.dto.ConfirmAttendanceResponse;
import com.hobbytree.hobbytree.domain.experience.entity.Experience;
import com.hobbytree.hobbytree.domain.experience.entity.ExperienceReservation;
import com.hobbytree.hobbytree.domain.experience.entity.ReservationStatus;
import com.hobbytree.hobbytree.domain.experience.repository.ExperienceReservationRepository;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyClaim;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyService;
import com.hobbytree.hobbytree.domain.tree.code.TreeErrorCode;
import com.hobbytree.hobbytree.domain.tree.entity.ActivityRecord;
import com.hobbytree.hobbytree.domain.tree.entity.TreeStatus;
import com.hobbytree.hobbytree.domain.tree.entity.UserHobby;
import com.hobbytree.hobbytree.domain.tree.repository.ActivityRecordRepository;
import com.hobbytree.hobbytree.domain.tree.repository.ActivityRecordRepository.GrowthCounts;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// API-39: confirms participation in a booked class/meetup and credits the mapped activity's
// growth once. API-29 (활동 완료 및 피드백) does not exist in this codebase yet (no code
// anywhere creates an ActivityRecord or sets growth_counted - see memory on API ownership), so
// this service applies growth itself, directly against activity_records' own dedup mechanism
// (growth_counted, the (user_hobby_id, activity_id) unique constraint) and the same per-stage
// threshold UserHobbyService's read side already assumes. If/when a teammate implements API-29
// for real, the two should be unified behind one shared method.
@Service
@RequiredArgsConstructor
public class ExperienceAttendanceService {
  private static final String ENDPOINT =
      "POST /api/v1/experience-reservations/{reservationId}/attendance";

  private final UserRepository users;
  private final ExperienceReservationRepository reservations;
  private final UserHobbyRepository userHobbies;
  private final ActivityRecordRepository activityRecords;
  private final IdempotencyKeyService idempotencyKeys;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  @Transactional
  public ConfirmAttendanceResponse confirm(
      Long userId, Long reservationId, String idempotencyKey, ConfirmAttendanceRequest request) {
    if (idempotencyKey == null || idempotencyKey.isBlank())
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);

    User user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);

    if (request.userHobbyId() == null
        || request.attendanceToken() == null
        || request.attendanceToken().isBlank())
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);

    String requestHash =
        RedisAuthStore.hash(
            "reservationId="
                + reservationId
                + ";userHobbyId="
                + request.userHobbyId()
                + ";attendanceToken="
                + request.attendanceToken());
    IdempotencyKeyClaim claim =
        idempotencyKeys.claim(userId, ENDPOINT, idempotencyKey, requestHash);
    switch (claim.outcome()) {
      case CONFLICT -> throw new ProjectException(GeneralErrorCode.IDEMPOTENCY_KEY_CONFLICT);
      case IN_PROGRESS -> throw new ProjectException(GeneralErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS);
      case REPLAY -> {
        return objectMapper.readValue(claim.responseBody(), ConfirmAttendanceResponse.class);
      }
      case CLAIMED -> {
        // fall through to build a fresh confirmation below
      }
    }

    try {
      ConfirmAttendanceResponse response = buildAttendance(userId, reservationId, request);
      idempotencyKeys.complete(
          claim.idempotencyKeyId(),
          claim.ownershipToken(),
          200,
          objectMapper.writeValueAsString(response));
      return response;
    } catch (RuntimeException e) {
      idempotencyKeys.release(claim.idempotencyKeyId(), claim.ownershipToken());
      throw e;
    }
  }

  private ConfirmAttendanceResponse buildAttendance(
      Long userId, Long reservationId, ConfirmAttendanceRequest request) {
    ExperienceReservation reservation =
        reservations
            .findOwnedForAttendance(reservationId, userId)
            .orElseThrow(() -> new ProjectException(ReservationErrorCode.NOT_FOUND));

    // API.md step 6: a repeat request for the same reservation+tree returns the existing
    // result - independent of the Idempotency-Key header, and skipping attendanceToken
    // re-verification entirely (nothing left to verify once already confirmed).
    if (reservation.getReservationStatus() == ReservationStatus.ATTENDED) {
      if (!request.userHobbyId().equals(reservation.getCreditedUserHobbyId()))
        throw new ProjectException(AttendanceErrorCode.NOT_CONFIRMED);
      return buildReplayResponse(reservation);
    }
    if (reservation.getReservationStatus() != ReservationStatus.CONFIRMED)
      throw new ProjectException(AttendanceErrorCode.NOT_CONFIRMED);

    verifyAttendanceToken(request.attendanceToken());

    return applyAttendance(userId, reservationId, request.userHobbyId());
  }

  // No real provider signs or confirms attendance tokens anywhere in the current API set (API.md
  // 비고: "현재 49개에는 제공자 콜백 API가 없다", the same gap ExperienceReservationService's
  // own class comment documents for CONFIRMED). A client-supplied token alone is never trusted
  // as verified ("단순 클라이언트 attended:true는 허용하지 않음"), so this always reports the
  // integration as unavailable - isolated into its own method so only this needs to change once
  // a real provider verification/callback exists.
  private void verifyAttendanceToken(String attendanceToken) {
    throw new ProjectException(AttendanceErrorCode.VERIFICATION_UNAVAILABLE);
  }

  // Public (not just called from confirm() above) so tests can reach it directly: through
  // confirm() it's only reachable once verifyAttendanceToken above can actually succeed, which
  // today it never can. ExperienceAttendanceIntegrationTests exercises it directly in the
  // meantime, testing business logic the real HTTP contract cannot currently reach.
  // Re-fetches the reservation by id rather than taking buildAttendance's already-loaded
  // instance: that one came from a transaction that (for a direct caller like a test) may
  // already be closed, and reservation.getExperience() is a lazy proxy that cannot reattach to
  // this method's own transaction once detached (ec2 profile, open-in-view:false).
  @Transactional
  public ConfirmAttendanceResponse applyAttendance(
      Long userId, Long reservationId, Long userHobbyId) {
    ExperienceReservation reservation =
        reservations
            .findOwnedForAttendance(reservationId, userId)
            .orElseThrow(() -> new ProjectException(ReservationErrorCode.NOT_FOUND));
    Experience experience = reservation.getExperience();
    UserHobby userHobby =
        userHobbies
            .findOwnedForAttendance(userHobbyId, userId)
            .orElseThrow(() -> new ProjectException(TreeErrorCode.NOT_FOUND));
    if (userHobby.getTreeStatus() != TreeStatus.ACTIVE)
      throw new ProjectException(TreeErrorCode.NOT_ACTIVE);

    Long experienceHobbyId =
        experience.getHobby() == null ? null : experience.getHobby().getHobbyId();
    if (experienceHobbyId == null || !experienceHobbyId.equals(userHobby.getHobby().getHobbyId()))
      throw new ProjectException(AttendanceErrorCode.HOBBY_MISMATCH);

    Activity activity = experience.getLinkedActivity();
    if (activity == null) throw new ProjectException(AttendanceErrorCode.NO_ACTIVITY_MAPPING);

    Instant now = clock.instant();
    short previousStage = userHobby.getCurrentStageNumber();
    GrowthCounts before =
        activityRecords.countGrowthByTreeIds(List.of(userHobbyId)).stream()
            .findFirst()
            .orElse(null);
    long totalBefore = before == null ? 0 : before.getTotalCount();
    long atCurrentStageBefore = before == null ? 0 : before.getCurrentStageCount();

    var existing =
        activityRecords.findByUserHobby_UserHobbyIdAndActivity_ActivityId(
            userHobbyId, activity.getActivityId());
    boolean growthApplied;
    ActivityRecord record;
    ActivityRecord creditedForReservation;
    if (existing.isPresent() && existing.get().getGrowthCounted()) {
      // Already counted from elsewhere (another experience reservation, or a future API-29
      // self-tracked completion) - "같은 활동의 기여를 한 번만 추가": reuse it, give no new
      // growth, and leave this reservation's own FK unlinked (the column is unique - only the
      // row that first credited it may point to it).
      record = existing.get();
      growthApplied = false;
      creditedForReservation = null;
    } else if (existing.isPresent()) {
      // "자체 진행 중인 활동 기록은 유지한다" - complete the existing row in place rather than
      // replacing it, so its stepProgress/attemptCount survive.
      record = existing.get();
      record.completeFromExperience(now, previousStage);
      growthApplied = true;
      creditedForReservation = record;
    } else {
      record =
          activityRecords.save(
              ActivityRecord.completeFromExperience(userHobby, activity, now, previousStage));
      growthApplied = true;
      creditedForReservation = record;
    }

    long totalAfter = growthApplied ? totalBefore + 1 : totalBefore;
    long atCurrentStageAfter = growthApplied ? atCurrentStageBefore + 1 : atCurrentStageBefore;
    boolean stageChanged =
        growthApplied
            && userHobby.advanceStageIfThresholdMet(
                atCurrentStageAfter, userHobby.getHobby().getActivitiesPerStage().intValue());

    reservation.confirmAttendance(now, userHobbyId, creditedForReservation);

    return new ConfirmAttendanceResponse(
        reservation.getReservationId(),
        reservation.getReservationId(),
        true,
        now,
        record.getActivityRecordId(),
        activity.getActivityId(),
        userHobbyId,
        new AttendanceGrowthResponse(
            growthApplied,
            totalAfter,
            previousStage,
            userHobby.getCurrentStageNumber(),
            stageChanged));
  }

  // Step 6's repeat-request path: recomputes the same shape from the reservation's own
  // already-persisted state, with growthApplied always false (no new growth on a replay).
  private ConfirmAttendanceResponse buildReplayResponse(ExperienceReservation reservation) {
    Long userHobbyId = reservation.getCreditedUserHobbyId();
    UserHobby userHobby =
        userHobbies
            .findOwnedByIdWithHobby(userHobbyId, reservation.getUser().getUserId())
            .orElseThrow(() -> new ProjectException(TreeErrorCode.NOT_FOUND));
    Activity activity = reservation.getExperience().getLinkedActivity();
    ActivityRecord record =
        reservation.getCreditedActivityRecord() != null
            ? reservation.getCreditedActivityRecord()
            : activityRecords
                .findByUserHobby_UserHobbyIdAndActivity_ActivityId(
                    userHobbyId, activity.getActivityId())
                .orElseThrow(() -> new ProjectException(AttendanceErrorCode.NO_ACTIVITY_MAPPING));
    GrowthCounts growth =
        activityRecords.countGrowthByTreeIds(List.of(userHobbyId)).stream()
            .findFirst()
            .orElse(null);
    short current = userHobby.getCurrentStageNumber();
    return new ConfirmAttendanceResponse(
        reservation.getReservationId(),
        reservation.getReservationId(),
        true,
        reservation.getAttendanceVerifiedAt(),
        record.getActivityRecordId(),
        activity.getActivityId(),
        userHobbyId,
        new AttendanceGrowthResponse(
            false, growth == null ? 0 : growth.getTotalCount(), current, current, false));
  }
}
