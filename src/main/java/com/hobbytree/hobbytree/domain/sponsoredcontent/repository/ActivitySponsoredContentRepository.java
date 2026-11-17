package com.hobbytree.hobbytree.domain.sponsoredcontent.repository;

import com.hobbytree.hobbytree.domain.sponsoredcontent.entity.ActivitySponsoredContent;
import com.hobbytree.hobbytree.domain.sponsoredcontent.entity.ActivitySponsoredContentId;
import com.hobbytree.hobbytree.domain.sponsoredcontent.entity.SponsoredContentStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivitySponsoredContentRepository
    extends JpaRepository<ActivitySponsoredContent, ActivitySponsoredContentId> {

  @Query(
      "select m from ActivitySponsoredContent m join fetch m.sponsoredContent"
          + " where m.id.activityId = :activityId"
          + " and m.sponsoredContent.status = :status"
          + " and (m.sponsoredContent.startsAt is null or m.sponsoredContent.startsAt <= :now)"
          + " and (m.sponsoredContent.endsAt is null or m.sponsoredContent.endsAt > :now)"
          + " order by m.sortOrder asc")
  List<ActivitySponsoredContent> findVisibleByActivityId(
      @Param("activityId") Long activityId,
      @Param("status") SponsoredContentStatus status,
      @Param("now") Instant now);
}
