package com.hobbytree.hobbytree.domain.activity.repository;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityRepository extends JpaRepository<Activity, Long> {
  /** Uses keyset filtering and at most one saved row per requesting user/activity. */
  @Query(
      """
      SELECT a.activityId AS activityId, a.activityName AS title,
             a.expectedTotalMinutes AS durationMinutes, a.estimatedCostMin AS estimatedCost,
             a.difficulty AS difficulty,
             CASE WHEN s.id.userId IS NOT NULL THEN TRUE ELSE FALSE END AS saved
      FROM Activity a
      LEFT JOIN SavedActivity s ON s.activity = a AND s.id.userId = :userId
      WHERE a.hobbyStage.hobbyStageId = :stageId
        AND a.reviewStatus = com.hobbytree.hobbytree.domain.activity.entity.ReviewStatus.PUBLISHED
        AND a.activityId > :afterId
      ORDER BY a.activityId ASC
      """)
  List<StageItem> findPublishedStagePage(
      @Param("stageId") Long stageId,
      @Param("userId") Long userId,
      @Param("afterId") long afterId,
      Pageable limit);

  interface StageItem {
    Long getActivityId();

    String getTitle();

    Integer getDurationMinutes();

    Integer getEstimatedCost();

    String getDifficulty();

    boolean getSaved();
  }

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("select a from Activity a where a.activityId = :activityId")
  Optional<Activity> findForSaving(@Param("activityId") Long activityId);

  // API-16's "입문 활동 기준 요약값": the published activity in each hobby's stage 0
  // (lowest activityId wins per hobby, picked in HobbyService). Ordering here, not a
  // GROUP BY, because an activity has no single "first" column to aggregate on besides
  // its own id.
  // JOIN FETCH hobbyStage/hobby: HobbyService reads a.getHobbyStage().getHobby() right after
  // this call returns. Without fetching them here, that's an extra per-row SELECT even with
  // open-in-view on, and a LazyInitializationException under the ec2 profile's
  // open-in-view:false if it ever runs outside a transaction.
  @Query(
      """
      SELECT a FROM Activity a
      JOIN FETCH a.hobbyStage hs
      JOIN FETCH hs.hobby
      WHERE hs.hobby.hobbyId IN :hobbyIds
        AND hs.stageNumber = 0
        AND a.reviewStatus = com.hobbytree.hobbytree.domain.activity.entity.ReviewStatus.PUBLISHED
      ORDER BY a.activityId ASC
      """)
  List<Activity> findIntroActivityCandidates(@Param("hobbyIds") Collection<Long> hobbyIds);
}
