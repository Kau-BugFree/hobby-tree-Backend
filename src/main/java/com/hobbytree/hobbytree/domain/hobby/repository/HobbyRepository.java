package com.hobbytree.hobbytree.domain.hobby.repository;

import com.hobbytree.hobbytree.domain.hobby.entity.Hobby;
import com.hobbytree.hobbytree.domain.hobby.entity.HobbyCategory;
import com.hobbytree.hobbytree.domain.hobby.entity.IndoorOutdoor;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HobbyRepository extends JpaRepository<Hobby, Long> {
  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("SELECT h FROM Hobby h WHERE h.hobbyId = :hobbyId AND h.isSupported = TRUE")
  Optional<Hobby> findForPlanting(@Param("hobbyId") Long hobbyId);

  // Catalog is small by design (3 fixed categories for MVP), so filtering/sorting/paging all
  // happen in the service layer over this unfiltered-by-DB-pagination candidate list, rather
  // than a single large native query joining activities/user_hobbies for cursor-correct paging.
  // Each optional filter gets its own "has*" boolean for the same reason as
  // UserNotificationRepository.findPage: Postgres can't type a bare "?" compared only via
  // "x IS NULL".
  @Query(
      """
      SELECT h FROM Hobby h
      WHERE h.isSupported = TRUE
        AND (:hasKeyword = FALSE
             OR LOWER(h.hobbyName) LIKE :keywordPattern
             OR LOWER(h.description) LIKE :keywordPattern)
        AND (:hasCategory = FALSE OR h.category = :category)
        AND (:hasIndoorOutdoor = FALSE OR h.indoorOutdoor = :indoorOutdoor)
      """)
  List<Hobby> findCandidates(
      @Param("hasKeyword") boolean hasKeyword,
      @Param("keywordPattern") String keywordPattern,
      @Param("hasCategory") boolean hasCategory,
      @Param("category") HobbyCategory category,
      @Param("hasIndoorOutdoor") boolean hasIndoorOutdoor,
      @Param("indoorOutdoor") IndoorOutdoor indoorOutdoor);
}
