package com.hobbytree.hobbytree.domain.region.repository;

import com.hobbytree.hobbytree.domain.region.entity.Region;
import com.hobbytree.hobbytree.domain.region.entity.RegionLevel;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegionRepository extends JpaRepository<Region, Long> {

  // Each optional filter gets its own "has*" boolean, same reason as
  // UserNotificationRepository.findPage: Postgres can't type a bare "?" compared only via
  // "x IS NULL". Keyset pagination on (name, regionId) - Pageable only bounds page size.
  @Query(
      """
      SELECT r FROM Region r
      WHERE r.level = :level
        AND (LOWER(r.name) LIKE :keywordPattern OR LOWER(r.fullName) LIKE :keywordPattern)
        AND (:hasParent = FALSE OR r.parentRegionId = :parentRegionId)
        AND (:hasCursor = FALSE
             OR r.name > :afterName
             OR (r.name = :afterName AND r.regionId > :afterId))
      ORDER BY r.name ASC, r.regionId ASC
      """)
  List<Region> findPage(
      @Param("level") RegionLevel level,
      @Param("keywordPattern") String keywordPattern,
      @Param("hasParent") boolean hasParent,
      @Param("parentRegionId") Long parentRegionId,
      @Param("hasCursor") boolean hasCursor,
      @Param("afterName") String afterName,
      @Param("afterId") Long afterId,
      Pageable pageable);
}
