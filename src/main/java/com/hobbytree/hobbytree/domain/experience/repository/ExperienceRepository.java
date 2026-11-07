package com.hobbytree.hobbytree.domain.experience.repository;

import com.hobbytree.hobbytree.domain.experience.entity.Experience;
import com.hobbytree.hobbytree.domain.experience.entity.ExperienceType;
import com.hobbytree.hobbytree.domain.experience.entity.ListingStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExperienceRepository extends JpaRepository<Experience, Long> {
  // Catalog is small by design (same posture as HobbyRepository.findCandidates) - filtering by
  // the soonest upcoming session, sorting and paging all happen in the service layer over this
  // unfiltered-by-DB-pagination candidate list. DRAFT/CANCELLED are never publicly browsable,
  // and neither is EXTERNAL - API-35's type filter only ever allows CLASS/MEETUP, so an
  // EXTERNAL listing must stay out of this catalog even when the client sends no type filter
  // at all, not just when it explicitly asks for CLASS or MEETUP.
  // JOIN FETCH hobby: ExperienceService reads hobby.hobbyName/thumbnailUrl right after this
  // call returns, which would otherwise be a LazyInitializationException under the ec2
  // profile's open-in-view:false.
  @Query(
      """
      SELECT e FROM Experience e
      LEFT JOIN FETCH e.hobby
      WHERE e.listingStatus IN ('COMING_SOON','OPEN','CLOSED')
        AND e.experienceType IN ('CLASS','MEETUP')
        AND (:hasHobby = FALSE OR e.hobby.hobbyId = :hobbyId)
        AND (:hasRegion = FALSE OR e.regionId = :regionId)
        AND (:hasType = FALSE OR e.experienceType = :type)
        AND (:hasStatus = FALSE OR e.listingStatus = :status)
      """)
  List<Experience> findCandidates(
      @Param("hasHobby") boolean hasHobby,
      @Param("hobbyId") Long hobbyId,
      @Param("hasRegion") boolean hasRegion,
      @Param("regionId") Long regionId,
      @Param("hasType") boolean hasType,
      @Param("type") ExperienceType type,
      @Param("hasStatus") boolean hasStatus,
      @Param("status") ListingStatus status);
}
