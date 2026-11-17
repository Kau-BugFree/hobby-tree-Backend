package com.hobbytree.hobbytree.domain.activity.repository;

import com.hobbytree.hobbytree.domain.activity.entity.SavedActivity;
import com.hobbytree.hobbytree.domain.activity.entity.SavedActivityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavedActivityRepository extends JpaRepository<SavedActivity, SavedActivityId> {
  @Modifying
  @Query(
      "delete from SavedActivity s where s.id.userId = :userId and s.id.activityId = :activityId")
  int deleteSaved(@Param("userId") Long userId, @Param("activityId") Long activityId);
}
