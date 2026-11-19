package com.hobbytree.hobbytree.domain.activity.repository;

import com.hobbytree.hobbytree.domain.activity.dto.ActivityRecordListResponse.Item;
import com.hobbytree.hobbytree.domain.activity.service.ActivityRecordCursor.Position;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ActivityRecordListRepository {
  private final JdbcTemplate jdbc;

  public boolean ownsTree(long userId, long treeId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM user_hobbies WHERE user_hobby_id=? AND user_id=?)",
            Boolean.class,
            treeId,
            userId));
  }

  public List<Item> page(long userId, long treeId, String status, Position after, int limit) {
    var args = new ArrayList<Object>();
    args.add(treeId);
    args.add(userId);
    var where = new StringBuilder();
    if (status != null) {
      where.append(" AND r.activity_status=?");
      args.add(status);
    }
    if (after != null) {
      if (after.startedAt() == null) {
        where.append(" AND r.started_at IS NULL AND r.activity_record_id<?");
        args.add(after.recordId());
      } else {
        where.append(
            " AND (r.started_at IS NULL OR r.started_at<? OR (r.started_at=? AND r.activity_record_id<?))");
        args.add(Timestamp.from(after.startedAt()));
        args.add(Timestamp.from(after.startedAt()));
        args.add(after.recordId());
      }
    }
    args.add(limit);
    // Bound the page before enriching it: no N+1 queries, live publication filter or OFFSET.
    String sql =
        """
        WITH page AS MATERIALIZED (
          SELECT r.* FROM activity_records r
          JOIN user_hobbies t ON t.user_hobby_id=r.user_hobby_id
          WHERE r.user_hobby_id=? AND t.user_id=?
        """
            + where
            + """
          ORDER BY r.started_at DESC NULLS LAST,r.activity_record_id DESC LIMIT ?
        )
        SELECT p.*,c.title AS snapshot_title,a.activity_name AS live_title,
               progress.required_total,progress.required_done,
               EXISTS(SELECT 1 FROM activity_growth_contributions g
                      WHERE g.origin_record_id=p.activity_record_id
                        AND g.user_hobby_id=p.user_hobby_id AND g.activity_id=p.activity_id)
                 AS growth_applied
        FROM page p
        LEFT JOIN activities a ON a.activity_id=p.activity_id
        LEFT JOIN activity_record_contents c ON c.record_id=p.activity_record_id
          AND c.snapshot_schema_version=1 AND p.record_format=2
        LEFT JOIN LATERAL (
          SELECT count(*) FILTER (WHERE s.is_required) AS required_total,
                 count(*) FILTER (WHERE s.is_required AND s.is_completed) AS required_done
          FROM activity_record_steps s WHERE s.record_id=p.activity_record_id AND p.record_format=2
        ) progress ON true
        ORDER BY p.started_at DESC NULLS LAST,p.activity_record_id DESC
        """;
    return jdbc.query(
        sql,
        (row, index) -> {
          boolean snapshot = row.getShort("record_format") == 2;
          long required = row.getLong("required_total");
          if (snapshot && (row.getString("snapshot_title") == null || required == 0))
            throw new IllegalStateException("Activity record snapshot requires explicit repair");
          return new Item(
              row.getLong("activity_record_id"),
              row.getLong("activity_id"),
              row.getString(snapshot ? "snapshot_title" : "live_title"),
              row.getString("activity_status"),
              snapshot ? (int) (100 * row.getLong("required_done") / required) : null,
              row.getTimestamp("started_at") == null
                  ? null
                  : row.getTimestamp("started_at").toInstant(),
              row.getTimestamp("finished_at") == null
                  ? null
                  : row.getTimestamp("finished_at").toInstant(),
              row.getObject("satisfaction", Integer.class),
              row.getString("perceived_difficulty"),
              row.getBoolean("growth_applied"));
        },
        args.toArray());
  }
}
