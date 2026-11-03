package com.hobbytree.hobbytree.domain.activity.repository;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityRepository extends JpaRepository<Activity, Long> {
  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("select a from Activity a where a.activityId = :activityId")
  Optional<Activity> findForSaving(@Param("activityId") Long activityId);
}
