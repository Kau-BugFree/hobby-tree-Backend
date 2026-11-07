package com.hobbytree.hobbytree.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit test-only counterpart to a migrated legacy record; never an application sync writer. */
public final class GrowthLedgerFixtures {
  private GrowthLedgerFixtures() {}

  public static void copyCreditIfCounted(JdbcTemplate jdbc, long treeId, long activityId) {
    jdbc.update(
        """
        INSERT INTO activity_growth_contributions
          (user_hobby_id,activity_id,origin_record_id,credited_stage_number,source,migrated_at)
        SELECT user_hobby_id,activity_id,activity_record_id,growth_stage_number,
               'LEGACY_RECORD',clock_timestamp()
        FROM activity_records WHERE user_hobby_id=? AND activity_id=? AND growth_counted
        """,
        treeId,
        activityId);
  }
}
