package com.hobbytree.hobbytree.domain.experience.repository;

import com.hobbytree.hobbytree.domain.experience.entity.ExperienceSession;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExperienceSessionRepository extends JpaRepository<ExperienceSession, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from ExperienceSession s where s.sessionId = :sessionId")
  Optional<ExperienceSession> findForReservation(@Param("sessionId") Long sessionId);

  // API-35's "다음 일정": only sessions that haven't started yet are candidates. The service
  // picks the earliest one per experience from this list.
  @Query(
      """
      SELECT s FROM ExperienceSession s
      WHERE s.experienceId IN :experienceIds AND s.startsAt >= :now
      ORDER BY s.startsAt ASC
      """)
  List<ExperienceSession> findUpcomingByExperienceIds(
      @Param("experienceIds") Collection<Long> experienceIds, @Param("now") Instant now);
}
