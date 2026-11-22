package com.hobbytree.hobbytree.domain.activity.repository;

import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ActivityRecordDetailRepository {
  private final JdbcTemplate jdbc;

  // No live activity/catalog join: only the authenticated owner's tree scopes this record.
  public Optional<Detail> findOwned(long recordId, long userId) {
    return jdbc
        .query(
            """
        SELECT r.*, t.tree_status
        FROM activity_records r JOIN user_hobbies t ON t.user_hobby_id=r.user_hobby_id
        WHERE r.activity_record_id=? AND t.user_id=? AND r.deleted_at IS NULL
        """,
            (row, index) ->
                new Detail(
                    row.getLong("activity_record_id"),
                    row.getLong("user_hobby_id"),
                    row.getLong("activity_id"),
                    row.getString("activity_status"),
                    row.getString("tree_status"),
                    row.getShort("record_format"),
                    row.getObject("record_version", Long.class),
                    row.getObject("elapsed_seconds", Integer.class),
                    row.getTimestamp("started_at") == null
                        ? null
                        : row.getTimestamp("started_at").toInstant(),
                    row.getTimestamp("finished_at") == null
                        ? null
                        : row.getTimestamp("finished_at").toInstant(),
                    row.getObject("satisfaction", Integer.class),
                    row.getString("perceived_difficulty"),
                    row.getString("feedback_memo")),
            recordId,
            userId)
        .stream()
        .findFirst();
  }

  public record Detail(
      long recordId,
      long treeId,
      long activityId,
      String status,
      String treeStatus,
      short format,
      Long version,
      Integer elapsedSeconds,
      Instant startedAt,
      Instant finishedAt,
      Integer satisfaction,
      String difficulty,
      String memo) {}
}
