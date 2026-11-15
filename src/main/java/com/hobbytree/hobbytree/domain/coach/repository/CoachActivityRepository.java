package com.hobbytree.hobbytree.domain.coach.repository;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface CoachActivityRepository extends Repository<Activity, Long> {
  @Query(
      """
      SELECT a FROM Activity a JOIN FETCH a.hobbyStage hs JOIN FETCH hs.hobby h
      WHERE a.activityCode IN :codes AND h.hobbyCode = :hobbyCode AND h.isSupported = true
        AND a.reviewStatus = com.hobbytree.hobbytree.domain.activity.entity.ReviewStatus.PUBLISHED
      """)
  List<Activity> findPublishedCodes(
      @Param("codes") Collection<String> codes, @Param("hobbyCode") String hobbyCode);
}
