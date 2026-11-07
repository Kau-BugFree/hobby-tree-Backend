package com.hobbytree.hobbytree.domain.experience.repository;

import com.hobbytree.hobbytree.domain.experience.entity.ExperienceReservation;
import com.hobbytree.hobbytree.domain.experience.entity.ReservationStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExperienceReservationRepository
    extends JpaRepository<ExperienceReservation, Long> {
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
}
