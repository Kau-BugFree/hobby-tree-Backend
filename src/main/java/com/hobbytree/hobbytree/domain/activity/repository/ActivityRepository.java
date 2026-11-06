package com.hobbytree.hobbytree.domain.activity.repository;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityRepository extends JpaRepository<Activity, Long> {
  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("select a from Activity a where a.activityId = :activityId")
  Optional<Activity> findForSaving(@Param("activityId") Long activityId);

  // API-16's "입문 활동 기준 요약값": the published activity in each hobby's stage 0
  // (lowest activityId wins per hobby, picked in HobbyService). Ordering here, not a
  // GROUP BY, because an activity has no single "first" column to aggregate on besides
  // its own id.
  @Query(
      """
      SELECT a FROM Activity a
      WHERE a.hobbyStage.hobby.hobbyId IN :hobbyIds
        AND a.hobbyStage.stageNumber = 0
        AND a.reviewStatus = com.hobbytree.hobbytree.domain.activity.entity.ReviewStatus.PUBLISHED
      ORDER BY a.activityId ASC
      """)
  List<Activity> findIntroActivityCandidates(@Param("hobbyIds") Collection<Long> hobbyIds);
}
