package com.hobbytree.hobbytree.domain.experience.service;

import com.hobbytree.hobbytree.domain.auth.code.AuthErrorCode;
import com.hobbytree.hobbytree.domain.auth.exception.AuthException;
import com.hobbytree.hobbytree.domain.auth.service.RedisAuthStore;
import com.hobbytree.hobbytree.domain.experience.code.ExperienceErrorCode;
import com.hobbytree.hobbytree.domain.experience.code.ReservationErrorCode;
import com.hobbytree.hobbytree.domain.experience.dto.CreateReservationRequest;
import com.hobbytree.hobbytree.domain.experience.dto.CreateReservationResponse;
import com.hobbytree.hobbytree.domain.experience.dto.MyReservationItemResponse;
import com.hobbytree.hobbytree.domain.experience.dto.MyReservationListResponse;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
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
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 50;
  // API.md's API-38 filter/response vocabulary calls the attended outcome COMPLETED; the
  // stored enum (inherited from before API-37, still CHECK-constrained on ATTENDED) never
  // renamed it. Translated at this boundary only - everything else maps straight through by
  // name, including REQUESTED (pre-API-37 legacy rows API.md never anticipated exposing, but
  // honest to show as what they actually are rather than disguise as a status they're not).
  private static final String COMPLETED_WIRE_STATUS = "COMPLETED";

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
          claim.idempotencyKeyId(),
          claim.ownershipToken(),
          201,
          objectMapper.writeValueAsString(response));
      return response;
    } catch (RuntimeException e) {
      idempotencyKeys.release(claim.idempotencyKeyId(), claim.ownershipToken());
      throw e;
    }
  }

  // API-38: 내 예약 목록.
  public MyReservationListResponse listMine(
      Long userId, String status, String cursor, Integer size) {
    User user =
        users.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.ACCESS_TOKEN));
    if (user.getAccountStatus() == AccountStatus.PENDING_ONBOARDING)
      throw new AuthException(AuthErrorCode.ONBOARDING_INCOMPLETE);

    ReservationStatus statusFilter = parseStatusFilter(status);
    int pageSize = parsePageSize(size);
    Cursor position = decodeCursor(cursor);

    List<ExperienceReservation> rows =
        reservations.findMyPage(
            userId,
            statusFilter != null,
            statusFilter,
            position != null,
            position == null ? Instant.EPOCH : position.bookedAt(),
            position == null ? 0L : position.id(),
            PageRequest.of(0, pageSize + 1));

    boolean hasNext = rows.size() > pageSize;
    List<ExperienceReservation> page = hasNext ? rows.subList(0, pageSize) : rows;

    // sessionId is a plain FK (not a @ManyToOne) - batch it separately, same pattern
    // ExperienceService.detail()/list() use for the same reason.
    Map<Long, ExperienceSession> sessionsById =
        sessions
            .findAllById(page.stream().map(ExperienceReservation::getSessionId).toList())
            .stream()
            .collect(Collectors.toMap(ExperienceSession::getSessionId, Function.identity()));

    List<MyReservationItemResponse> items =
        page.stream().map(r -> toItem(r, sessionsById.get(r.getSessionId()))).toList();
    String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1)) : null;
    return new MyReservationListResponse(items, nextCursor, hasNext);
  }

  private MyReservationItemResponse toItem(ExperienceReservation r, ExperienceSession session) {
    Experience experience = r.getExperience();
    return new MyReservationItemResponse(
        r.getReservationId(),
        experience.getExperienceId(),
        experience.getTitle(),
        r.getSessionId(),
        session.getStartsAt(),
        r.getUserHobbyId(),
        toWireStatus(r.getReservationStatus()),
        toVerificationStatus(r.getReservationStatus()),
        r.getAttendanceVerifiedAt() != null,
        experience.getExternalUrl(),
        experience.getCancellationPolicyUrl(),
        r.getBookedAt());
  }

  private String toWireStatus(ReservationStatus status) {
    return status == ReservationStatus.ATTENDED ? COMPLETED_WIRE_STATUS : status.name();
  }

  // CONFIRMED/ATTENDED can only be reached once a provider has actually verified the
  // reservation (API-39 only promotes a CONFIRMED row - see its own 로직 설명 step 1), so both
  // read as VERIFIED. CANCELLED collapses to NONE regardless of what it was before
  // cancelling - no prior-state history is kept, and verification is moot once cancelled.
  private String toVerificationStatus(ReservationStatus status) {
    return switch (status) {
      case CONFIRMED, ATTENDED -> "VERIFIED";
      case PENDING_VERIFICATION -> "PENDING";
      case REDIRECTED, CANCELLED, REQUESTED -> "NONE";
    };
  }

  private ReservationStatus parseStatusFilter(String status) {
    if (status == null) return null;
    if (status.equals(COMPLETED_WIRE_STATUS)) return ReservationStatus.ATTENDED;
    return switch (status) {
      case "REDIRECTED" -> ReservationStatus.REDIRECTED;
      case "PENDING_VERIFICATION" -> ReservationStatus.PENDING_VERIFICATION;
      case "CONFIRMED" -> ReservationStatus.CONFIRMED;
      case "CANCELLED" -> ReservationStatus.CANCELLED;
      default -> throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    };
  }

  private int parsePageSize(Integer size) {
    if (size == null) return DEFAULT_PAGE_SIZE;
    if (size < 1 || size > MAX_PAGE_SIZE)
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    return size;
  }

  // Opaque cursor: base64("{bookedAt}_{reservationId}") - same encoding NotificationService
  // uses and for the same reason (encoding the full Instant, not epochMilli, keeps the
  // sub-millisecond-precision "bookedAt < cursor" comparison from skipping rows).
  private Cursor decodeCursor(String cursor) {
    if (cursor == null) return null;
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = decoded.split("_", 2);
      return new Cursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
    } catch (RuntimeException e) {
      throw new ProjectException(GeneralErrorCode.COMMON_BAD_REQUEST);
    }
  }

  private String encodeCursor(ExperienceReservation last) {
    String raw = last.getBookedAt() + "_" + last.getReservationId();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private record Cursor(Instant bookedAt, Long id) {}

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
            request.externalReservationId(),
            userHobbyId);
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
