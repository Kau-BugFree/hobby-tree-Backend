package com.hobbytree.hobbytree.domain.tree.repository;

import com.hobbytree.hobbytree.domain.tree.entity.ActivityRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityRecordRepository extends JpaRepository<ActivityRecord, Long> {
  @Query(
      """
      SELECT ar FROM ActivityRecord ar JOIN FETCH ar.activity
      WHERE ar.userHobby.userHobbyId = :treeId
        AND ar.activityStatus = com.hobbytree.hobbytree.domain.tree.entity.ActivityStatus.IN_PROGRESS
      ORDER BY ar.updatedAt DESC, ar.activityRecordId DESC
      """)
  List<ActivityRecord> findCurrentActivity(@Param("treeId") Long treeId, Pageable pageable);

  @Query("SELECT MAX(ar.updatedAt) FROM ActivityRecord ar WHERE ar.userHobby.userHobbyId = :treeId")
  Optional<Instant> findLastActivityAt(@Param("treeId") Long treeId);

  @Query(
      """
      SELECT uh.userHobbyId AS userHobbyId,
             COUNT(g) AS totalCount,
             SUM(CASE WHEN g.creditedStageNumber = uh.currentStageNumber THEN 1 ELSE 0 END)
                 AS currentStageCount
      FROM ActivityGrowthContribution g JOIN g.userHobby uh
      WHERE uh.userHobbyId IN :treeIds
      GROUP BY uh.userHobbyId
      """)
  List<GrowthCounts> countGrowthByTreeIds(@Param("treeIds") Collection<Long> treeIds);

  interface GrowthCounts {
    Long getUserHobbyId();

    long getTotalCount();

    long getCurrentStageCount();
  }
}
