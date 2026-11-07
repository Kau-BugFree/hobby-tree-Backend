package com.hobbytree.hobbytree.domain.tree.repository;

import com.hobbytree.hobbytree.domain.tree.entity.ActivityGrowthContribution;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Ledger persistence only; granting credit and authorization belong to future domain writers. */
public interface ActivityGrowthContributionRepository
    extends JpaRepository<ActivityGrowthContribution, Long> {
  Optional<ActivityGrowthContribution> findByOriginRecordId(Long originRecordId);

  Optional<ActivityGrowthContribution> findByUserHobby_UserHobbyIdAndActivityId(
      Long userHobbyId, Long activityId);

  /** Read-only cutover audit; never repairs an old writer's missing or changed contribution. */
  @Query(
      value =
          """
          SELECT count(*) FROM activity_records r
          FULL JOIN activity_growth_contributions g ON g.origin_record_id=r.activity_record_id
          WHERE (r.growth_counted IS TRUE AND
                 (g.contribution_id IS NULL OR g.user_hobby_id IS DISTINCT FROM r.user_hobby_id
                  OR g.activity_id IS DISTINCT FROM r.activity_id
                  OR g.credited_stage_number IS DISTINCT FROM r.growth_stage_number))
             OR (g.contribution_id IS NOT NULL AND r.growth_counted IS DISTINCT FROM TRUE)
          """,
      nativeQuery = true)
  long countLegacyMismatches();
}
