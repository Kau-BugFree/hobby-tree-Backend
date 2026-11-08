package com.hobbytree.hobbytree.domain.experience.repository;

import com.hobbytree.hobbytree.domain.experience.entity.ExperienceReservation;
import com.hobbytree.hobbytree.domain.experience.entity.ReservationStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExperienceReservationRepository
    extends JpaRepository<ExperienceReservation, Long> {
  boolean existsByExperience_ExperienceIdAndSessionIdIsNullAndReservationStatusNot(
      Long experienceId, com.hobbytree.hobbytree.domain.experience.entity.ReservationStatus status);

  // API-39: locks the reservation row (not its experience/hobby/linkedActivity - those are
  // nullable associations, and Postgres rejects FOR UPDATE through an outer join) so two
  // concurrent attendance confirmations for the same reservation serialize instead of both
  // reading it as CONFIRMED and double-crediting growth.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT r FROM ExperienceReservation r WHERE r.reservationId = :reservationId AND r.user.userId = :userId")
  Optional<ExperienceReservation> findOwnedForAttendance(
      @Param("reservationId") Long reservationId, @Param("userId") Long userId);

  // API-37's own bookability check: is this specific session already full?
  long countBySessionIdAndReservationStatusNot(Long sessionId, ReservationStatus reservationStatus);

  // API-36's remainingSeats: every non-CANCELLED reservation still occupies a seat
  // (REDIRECTED/PENDING_VERIFICATION included - the member was already sent to book it).
  @Query(
      """
      SELECT r.sessionId AS sessionId, COUNT(r) AS activeCount
      FROM ExperienceReservation r
      WHERE r.sessionId IN :sessionIds AND r.reservationStatus <> 'CANCELLED'
      GROUP BY r.sessionId
      """)
  List<SessionReservationCount> countActiveBySessionIds(
      @Param("sessionIds") Collection<Long> sessionIds);

  interface SessionReservationCount {
    Long getSessionId();

    Long getActiveCount();
  }

  // API-38: a legacy row with no resolved session_id can't produce the response's required
  // sessionId/startsAt, so it's excluded here rather than surfaced with those fields null -
  // same reasoning ExperienceReservationService already applies when it blocks new bookings
  // for this exact case. Keyset pagination: Pageable here only bounds the page size (its own
  // Sort is unused), ordering is fixed by API-38 ("예약 생성 시각·ID 내림차순") and expressed
  // in the JPQL below - same pattern as UserNotificationRepository.findPage.
  @Query(
      """
      SELECT r FROM ExperienceReservation r
      JOIN FETCH r.experience
      WHERE r.user.userId = :userId
        AND r.sessionId IS NOT NULL
        AND (:hasStatus = FALSE OR r.reservationStatus = :status)
        AND (:hasCursor = FALSE
             OR r.bookedAt < :beforeBookedAt
             OR (r.bookedAt = :beforeBookedAt AND r.reservationId < :beforeId))
      ORDER BY r.bookedAt DESC, r.reservationId DESC
      """)
  List<ExperienceReservation> findMyPage(
      @Param("userId") Long userId,
      @Param("hasStatus") boolean hasStatus,
      @Param("status") ReservationStatus status,
      @Param("hasCursor") boolean hasCursor,
      @Param("beforeBookedAt") Instant beforeBookedAt,
      @Param("beforeId") Long beforeId,
      Pageable pageable);
}
