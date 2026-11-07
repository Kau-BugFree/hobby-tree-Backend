package com.hobbytree.hobbytree.domain.experience.repository;

import com.hobbytree.hobbytree.domain.experience.entity.ExperienceSession;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExperienceSessionRepository extends JpaRepository<ExperienceSession, Long> {
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
