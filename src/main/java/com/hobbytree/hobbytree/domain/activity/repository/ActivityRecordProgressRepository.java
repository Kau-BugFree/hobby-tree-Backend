package com.hobbytree.hobbytree.domain.activity.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ActivityRecordProgressRepository {
  private final JdbcTemplate jdbc;

  public Optional<Long> findOwnedTreeId(long recordId, long userId) {
    return jdbc
        .queryForList(
            """
        SELECT r.user_hobby_id FROM activity_records r
        JOIN user_hobbies t ON t.user_hobby_id=r.user_hobby_id
        WHERE r.activity_record_id=? AND t.user_id=? AND r.deleted_at IS NULL
        """,
            Long.class,
            recordId,
            userId)
        .stream()
        .findFirst();
  }

  public Optional<LockedRecord> lock(long recordId, long treeId) {
    return jdbc
        .query(
            """
        SELECT activity_status,record_format,record_version FROM activity_records
        WHERE activity_record_id=? AND user_hobby_id=? AND deleted_at IS NULL FOR UPDATE
        """,
            (row, index) ->
                new LockedRecord(row.getString(1), row.getShort(2), row.getObject(3, Long.class)),
            recordId,
            treeId)
        .stream()
        .findFirst();
  }

  public Optional<SavedRecord> advance(long recordId, long treeId, long version, Integer elapsed) {
    return jdbc
        .query(
            """
        UPDATE activity_records
        SET record_version=record_version+1,elapsed_seconds=COALESCE(?::integer,elapsed_seconds),
            updated_at=GREATEST(clock_timestamp(),updated_at+interval '1 microsecond')
        WHERE activity_record_id=? AND user_hobby_id=? AND record_version=?
          AND record_format=2 AND activity_status='IN_PROGRESS' AND deleted_at IS NULL
        RETURNING record_version,elapsed_seconds,updated_at
        """,
            (row, index) ->
                new SavedRecord(row.getLong(1), row.getInt(2), row.getTimestamp(3).toInstant()),
            elapsed,
            recordId,
            treeId,
            version)
        .stream()
        .findFirst();
  }

  public void replaceCompleted(long recordId, List<Long> completed) {
    jdbc.update("UPDATE activity_record_steps SET is_completed=false WHERE record_id=?", recordId);
    for (long stepId : completed) {
      if (jdbc.update(
              "UPDATE activity_record_steps SET is_completed=true WHERE record_id=? AND step_id=?",
              recordId,
              stepId)
          != 1)
        throw new IllegalStateException("Validated snapshot membership changed during save");
    }
  }

  public record LockedRecord(String status, short format, Long version) {}

  public record SavedRecord(long version, int elapsedSeconds, Instant updatedAt) {}
}
