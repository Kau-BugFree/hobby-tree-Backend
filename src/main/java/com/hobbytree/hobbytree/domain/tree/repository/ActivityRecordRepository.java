package com.hobbytree.hobbytree.domain.tree.repository;

import com.hobbytree.hobbytree.domain.tree.entity.ActivityRecord;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityRecordRepository extends JpaRepository<ActivityRecord, Long> {
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
