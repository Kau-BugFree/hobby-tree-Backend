package com.hobbytree.hobbytree.domain.experience.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.experience.code.ExperienceErrorCode;
import com.hobbytree.hobbytree.domain.experience.code.ReservationErrorCode;
import com.hobbytree.hobbytree.domain.experience.dto.CreateReservationRequest;
import com.hobbytree.hobbytree.domain.experience.dto.CreateReservationResponse;
import com.hobbytree.hobbytree.domain.experience.entity.Experience;
import com.hobbytree.hobbytree.domain.experience.entity.ExperienceReservation;
import com.hobbytree.hobbytree.domain.experience.entity.ExperienceSession;
import com.hobbytree.hobbytree.domain.experience.entity.ListingStatus;
import com.hobbytree.hobbytree.domain.experience.entity.ReservationStatus;
import com.hobbytree.hobbytree.domain.experience.repository.ExperienceRepository;
import com.hobbytree.hobbytree.domain.experience.repository.ExperienceReservationRepository;
import com.hobbytree.hobbytree.domain.experience.repository.ExperienceSessionRepository;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyClaim;
import com.hobbytree.hobbytree.domain.idempotency.service.IdempotencyKeyService;
import com.hobbytree.hobbytree.domain.tree.code.TreeErrorCode;
import com.hobbytree.hobbytree.domain.tree.entity.UserHobby;
import com.hobbytree.hobbytree.domain.tree.repository.UserHobbyRepository;
import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.User;
import com.hobbytree.hobbytree.domain.user.repository.UserRepository;
import com.hobbytree.hobbytree.global.apiPayload.code.GeneralErrorCode;
import com.hobbytree.hobbytree.global.exception.ProjectException;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// API-37: this only records that a member was redirected to (or submitted a reference from)
// an external booking flow - it never confirms a reservation itself. No provider callback
// exists anywhere in the current API set (API.md's own 비고: "현재 49개에는 제공자 콜백
// API가 없다"), so nothing in this codebase ever promotes PENDING_VERIFICATION to CONFIRMED;
// that remains a future, separate mechanism.
@Service
@RequiredArgsConstructor
public class ExperienceReservationService {
  private static final String ENDPOINT = "POST /api/v1/experiences/{experienceId}/reservations";
  private static final int MAX_EXTERNAL_REFERENCE_LENGTH = 100;

  private final UserRepository users;
  private final ExperienceRepository experiences;
  private final ExperienceSessionRepository sessions;
  private final UserHobbyRepository userHobbies;
  private final ExperienceReservationRepository reservations;
  private final IdempotencyKeyService idempotencyKeys;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  @Transactional
  public CreateReservationResponse create(
      Long userId, Long experienceId, String idempotencyKey, CreateReservationRequest request) {
    if (idempotencyKey == null || idempotencyKey.isBlank())
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);

    User user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);

    if (request.sessionId() == null)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    String externalReservationId = request.externalReservationId();
    if (externalReservationId != null
        && (externalReservationId.isBlank()
            || externalReservationId.length() > MAX_EXTERNAL_REFERENCE_LENGTH))
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);

    String requestHash = RedisAuthStore.hash(canonicalRequest(experienceId, request));
    IdempotencyKeyClaim claim =
        idempotencyKeys.claim(userId, ENDPOINT, idempotencyKey, requestHash);
    switch (claim.outcome()) {
      case CONFLICT -> throw new ProjectException(GeneralErrorCode.IDEMPOTENCY_KEY_CONFLICT);
      case IN_PROGRESS -> throw new ProjectException(GeneralErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS);
      case REPLAY -> {
        return objectMapper.readValue(claim.responseBody(), CreateReservationResponse.class);
      }
      case CLAIMED -> {
        // fall through to build a fresh reservation below
      }
    }

    try {
      CreateReservationResponse response = buildReservation(user, experienceId, request);
      idempotencyKeys.complete(
          claim.idempotencyKeyId(), 201, objectMapper.writeValueAsString(response));
      return response;
    } catch (RuntimeException e) {
      idempotencyKeys.release(claim.idempotencyKeyId());
      throw e;
    }
  }

  private CreateReservationResponse buildReservation(
      User user, Long experienceId, CreateReservationRequest request) {
    // Same visibility rule as API-36's detail - DRAFT/CANCELLED/EXTERNAL never shown.
    Experience experience =
        experiences
            .findDetailCandidateById(experienceId)
            .orElseThrow(
                () -> new ProjectException(ExperienceErrorCode.RESERVATION_TARGET_NOT_FOUND));

    ExperienceSession session =
        sessions
            .findForReservation(request.sessionId())
            .filter(s -> s.getExperienceId().equals(experienceId))
            .orElseThrow(
                () -> new ProjectException(ExperienceErrorCode.RESERVATION_TARGET_NOT_FOUND));

    // "마감·취소된 일정" - a session can only be booked while its experience is OPEN, it hasn't
    // already started, and (if a capacity was recorded) a seat is actually still free - same
    // remainingSeats derivation API-36's detail uses.
    // A legacy reservation with no unambiguous session must be reconciled first.
    if (reservations.existsByExperience_ExperienceIdAndSessionIdIsNullAndReservationStatusNot(
        experienceId, ReservationStatus.CANCELLED))
      throw new ProjectException(ReservationErrorCode.NOT_BOOKABLE);
    boolean full =
        session.getCapacity() != null
            && reservations.countBySessionIdAndReservationStatusNot(
                    session.getSessionId(), ReservationStatus.CANCELLED)
                >= session.getCapacity();
    boolean bookable =
        experience.getListingStatus() == ListingStatus.OPEN
            && !full
            && !session.getStartsAt().isBefore(clock.instant());
    if (!bookable) throw new ProjectException(ReservationErrorCode.NOT_BOOKABLE);

    Long userHobbyId = request.userHobbyId();
    if (userHobbyId != null) {
      UserHobby userHobby =
          userHobbies
              .findOwnedByIdWithHobby(userHobbyId, user.getUserId())
              .orElseThrow(() -> new ProjectException(TreeErrorCode.NOT_FOUND));
      Long experienceHobbyId =
          experience.getHobby() == null ? null : experience.getHobby().getHobbyId();
      if (experienceHobbyId == null || !experienceHobbyId.equals(userHobby.getHobby().getHobbyId()))
        throw new ProjectException(ReservationErrorCode.HOBBY_MISMATCH);
    }

    ExperienceReservation reservation =
        ExperienceReservation.of(
            "RSV-" + UUID.randomUUID(),
            experience,
            session.getSessionId(),
            user,
            experience.getProviderId(),
            request.externalReservationId());
    try {
      reservations.saveAndFlush(reservation);
    } catch (DataIntegrityViolationException e) {
      // Lost the race on (session_id,user_id), (provider_id,external_booking_ref), or - when
      // there's no provider to scope by - (experience_id,external_booking_ref). All three mean
      // "already reserved/linked" to this caller or reference.
      throw new ProjectException(ReservationErrorCode.NOT_BOOKABLE);
    }

    String verificationStatus =
        reservation.getReservationStatus() == ReservationStatus.PENDING_VERIFICATION
            ? "PENDING"
            : "NONE";
    return new CreateReservationResponse(
        reservation.getReservationId(),
        experienceId,
        session.getSessionId(),
        userHobbyId,
        reservation.getReservationStatus().name(),
        verificationStatus,
        reservation.getExternalBookingRef(),
        experience.getExternalUrl(),
        reservation.getBookedAt());
  }

  private String canonicalRequest(Long experienceId, CreateReservationRequest request) {
    return "experienceId="
        + experienceId
        + ";sessionId="
        + request.sessionId()
        + ";userHobbyId="
        + request.userHobbyId()
        + ";externalReservationId="
        + request.externalReservationId();
  }
}
