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

  // API-39: the one row (unique per (user_hobby_id, activity_id)) this tree may already have
  // for this activity, from a self-tracked attempt or an earlier experience credit.
  Optional<ActivityRecord> findByUserHobby_UserHobbyIdAndActivity_ActivityId(
      Long userHobbyId, Long activityId);

  @Query(
      """
      SELECT uh.userHobbyId AS userHobbyId,
             COUNT(ar) AS totalCount,
             SUM(CASE WHEN ar.growthStageNumber = uh.currentStageNumber THEN 1 ELSE 0 END)
                 AS currentStageCount
      FROM ActivityRecord ar JOIN ar.userHobby uh
      WHERE uh.userHobbyId IN :treeIds AND ar.growthCounted = TRUE
      GROUP BY uh.userHobbyId
      """)
  List<GrowthCounts> countGrowthByTreeIds(@Param("treeIds") Collection<Long> treeIds);

  interface GrowthCounts {
    Long getUserHobbyId();

    long getTotalCount();

    long getCurrentStageCount();
  }
}
