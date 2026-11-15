package com.hobbytree.hobbytree.domain.hobby.repository;

import com.hobbytree.hobbytree.domain.hobby.entity.HobbyStage;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HobbyStageRepository extends JpaRepository<HobbyStage, Long> {
  @Query(
      """
      SELECT hs FROM HobbyStage hs JOIN FETCH hs.hobby h
      WHERE hs.hobbyStageId = :stageId AND h.isSupported = TRUE
      """)
  Optional<HobbyStage> findPublicWithHobby(@Param("stageId") Long stageId);

  /** Counts published activities in one query, retaining stages with no public activities. */
  @Query(
      """
      SELECT hs.hobbyStageId AS stageId, hs.stageNumber AS stageNumber,
             hs.stageName AS name, hs.stageDescription AS description,
             COUNT(a.activityId) AS activityCount
      FROM HobbyStage hs
      LEFT JOIN Activity a ON a.hobbyStage = hs
        AND a.reviewStatus = com.hobbytree.hobbytree.domain.activity.entity.ReviewStatus.PUBLISHED
      WHERE hs.hobby.hobbyId = :hobbyId
      GROUP BY hs.hobbyStageId, hs.stageNumber, hs.stageName, hs.stageDescription
      ORDER BY hs.stageNumber ASC, hs.hobbyStageId ASC
      """)
  List<StageSummary> findStageSummaries(@Param("hobbyId") Long hobbyId);

  interface StageSummary {
    Long getStageId();

    short getStageNumber();

    String getName();

    String getDescription();

    long getActivityCount();
  }

  // Keep stages without published activities. Scalar rows avoid lazy loading and per-stage queries.
  @Query(
      """
      SELECT hs.hobbyStageId AS stageId, hs.stageNumber AS stageNumber,
             hs.stageName AS name, a.activityId AS activityId
      FROM HobbyStage hs
      LEFT JOIN Activity a ON a.hobbyStage = hs
        AND a.reviewStatus = com.hobbytree.hobbytree.domain.activity.entity.ReviewStatus.PUBLISHED
      WHERE hs.hobby.hobbyId = :hobbyId
      ORDER BY hs.stageNumber ASC, hs.hobbyStageId ASC, a.activityId ASC
      """)
  List<RoadmapEntry> findRoadmapEntries(@Param("hobbyId") Long hobbyId);

  interface RoadmapEntry {
    Long getStageId();

    short getStageNumber();

    String getName();

    Long getActivityId();
  }

  // API-17's totalStages.
  long countByHobby_HobbyId(Long hobbyId);

  @Query(
      """
      SELECT hs.hobby.hobbyId AS hobbyId, hs.stageNumber AS stageNumber, hs.stageName AS stageName
      FROM HobbyStage hs
      WHERE hs.hobby.hobbyId IN :hobbyIds
      """)
  List<StageName> findStageNamesByHobbyIds(@Param("hobbyIds") Collection<Long> hobbyIds);

  interface StageName {
    Long getHobbyId();

    short getStageNumber();

    String getStageName();
  }
}
