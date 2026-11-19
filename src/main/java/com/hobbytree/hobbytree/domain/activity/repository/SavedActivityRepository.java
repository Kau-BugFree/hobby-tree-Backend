package com.hobbytree.hobbytree.domain.activity.repository;

import com.hobbytree.hobbytree.domain.activity.entity.SavedActivity;
import com.hobbytree.hobbytree.domain.activity.entity.SavedActivityId;
import com.hobbytree.hobbytree.domain.hobby.entity.IndoorOutdoor;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavedActivityRepository extends JpaRepository<SavedActivity, SavedActivityId> {
  @Modifying
  @Query(
      "delete from SavedActivity s where s.id.userId = :userId and s.id.activityId = :activityId")
  int deleteSaved(@Param("userId") Long userId, @Param("activityId") Long activityId);

  // Keep hidden rows in the page. Boolean switches avoid untyped NULL parameters in PostgreSQL.
  @Query(
      """
      SELECT s.id.activityId AS activityId, s.savedAt AS savedAt,
             CASE WHEN a.reviewStatus = com.hobbytree.hobbytree.domain.activity.entity.ReviewStatus.PUBLISHED
                       AND h.isSupported = TRUE THEN TRUE ELSE FALSE END AS available,
             a.activityName AS title, COALESCE(a.thumbnailUrl, h.thumbnailUrl) AS thumbnailUrl,
             a.expectedTotalMinutes AS durationMinutes, a.estimatedCostMin AS estimatedCost,
             a.difficulty AS difficulty, h.indoorOutdoor AS indoorOutdoor,
             h.hobbyId AS hobbyId, h.hobbyName AS hobbyName,
             hs.hobbyStageId AS stageId, hs.stageNumber AS stageNumber
      FROM SavedActivity s
      JOIN s.activity a JOIN a.hobbyStage hs JOIN hs.hobby h
      WHERE s.id.userId = :userId
        AND (:hasHobby = FALSE OR h.hobbyId = :hobbyId)
        AND (:hasCursor = FALSE OR s.savedAt < :beforeSavedAt
             OR (s.savedAt = :beforeSavedAt AND s.id.activityId < :beforeActivityId))
      ORDER BY s.savedAt DESC, s.id.activityId DESC
      """)
  List<SavedItem> findMyPage(
      @Param("userId") long userId,
      @Param("hasHobby") boolean hasHobby,
      @Param("hobbyId") long hobbyId,
      @Param("hasCursor") boolean hasCursor,
      @Param("beforeSavedAt") Instant beforeSavedAt,
      @Param("beforeActivityId") long beforeActivityId,
      Pageable pageable);

  interface SavedItem {
    Long getActivityId();

    Instant getSavedAt();

    boolean getAvailable();

    String getTitle();

    String getThumbnailUrl();

    Integer getDurationMinutes();

    Integer getEstimatedCost();

    String getDifficulty();

    IndoorOutdoor getIndoorOutdoor();

    Long getHobbyId();

    String getHobbyName();

    Long getStageId();

    Short getStageNumber();
  }
}
