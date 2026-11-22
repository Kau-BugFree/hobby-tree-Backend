package com.hobbytree.hobbytree.domain.activity.repository;

import com.hobbytree.hobbytree.domain.activity.dto.CompleteActivityRequest;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ActivityRecordCompletionRepository {
  private final JdbcTemplate jdbc;

  public Optional<LockedRecord> lock(long recordId, long treeId) {
    return jdbc
        .query(
            """
        SELECT activity_id,activity_status,record_format,record_version FROM activity_records
        WHERE activity_record_id=? AND user_hobby_id=? AND deleted_at IS NULL FOR UPDATE
        """,
            (row, i) ->
                new LockedRecord(
                    row.getLong(1),
                    row.getString(2),
                    row.getShort(3),
                    row.getObject(4, Long.class)),
            recordId,
            treeId)
        .stream()
        .findFirst();
  }

  public int lockGrowthRule(long hobbyId) {
    return jdbc.queryForObject(
        "SELECT activities_per_stage FROM hobbies WHERE hobby_id=? FOR SHARE",
        Integer.class,
        hobbyId);
  }

  public GrowthState growth(long treeId, short stage) {
    return jdbc.queryForObject(
        """
        SELECT count(*),count(*) FILTER(WHERE COALESCE(progress_stage_number,credited_stage_number)=?)
        FROM activity_growth_contributions WHERE user_hobby_id=?
        """,
        (row, i) -> new GrowthState(row.getLong(1), row.getLong(2)),
        stage,
        treeId);
  }

  public boolean hasMirrorMismatch(long treeId) {
    return jdbc.queryForObject(
        """
        SELECT EXISTS(SELECT 1 FROM activity_records r
          LEFT JOIN activity_growth_contributions g ON g.origin_record_id=r.activity_record_id
          WHERE r.user_hobby_id=? AND (
            (r.growth_counted AND (g.contribution_id IS NULL OR COALESCE(g.progress_stage_number,g.credited_stage_number) IS DISTINCT FROM r.growth_stage_number))
            OR (g.contribution_id IS NOT NULL AND (r.growth_counted IS DISTINCT FROM TRUE
                  OR r.deleted_at IS NOT NULL OR r.activity_status<>'COMPLETED'))))
        """,
        Boolean.class,
        treeId);
  }

  public boolean hasContribution(long treeId, long activityId) {
    return jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM activity_growth_contributions WHERE user_hobby_id=? AND activity_id=?)",
        Boolean.class,
        treeId,
        activityId);
  }

  public Optional<FinishedRecord> finish(
      long recordId,
      long treeId,
      long version,
      CompleteActivityRequest request,
      boolean growthApplied,
      short stage) {
    var feedback = request.feedback();
    return jdbc
        .query(
            """
        WITH stamp AS MATERIALIZED (
          SELECT GREATEST(clock_timestamp(),started_at,updated_at+interval '1 microsecond') AS at
          FROM activity_records WHERE activity_record_id=?)
        UPDATE activity_records r SET activity_status=?,record_version=record_version+1,
          finished_at=stamp.at,updated_at=stamp.at,satisfaction=?,perceived_difficulty=?,
          feedback_memo=?,not_done_reason=?,growth_counted=?,growth_stage_number=?
        FROM stamp WHERE r.activity_record_id=? AND r.user_hobby_id=? AND r.record_version=?
          AND r.record_format=2 AND r.activity_status='IN_PROGRESS' AND r.deleted_at IS NULL
        RETURNING r.record_version,r.finished_at
        """,
            (row, i) -> new FinishedRecord(row.getLong(1), row.getTimestamp(2).toInstant()),
            recordId,
            request.status(),
            feedback == null ? null : feedback.satisfactionScore(),
            feedback == null ? null : feedback.perceivedDifficulty(),
            feedback == null ? null : feedback.memo(),
            request.notDoneReason(),
            growthApplied,
            growthApplied ? stage : null,
            recordId,
            treeId,
            version)
        .stream()
        .findFirst();
  }

  public void credit(long recordId, long treeId, long activityId, short stage, Instant at) {
    jdbc.update(
        """
        INSERT INTO activity_growth_contributions(user_hobby_id,activity_id,origin_record_id,credited_stage_number,source,credited_at)
        VALUES(?,?,?,?,'SELF',?)
        """,
        treeId,
        activityId,
        recordId,
        stage,
        java.sql.Timestamp.from(at));
  }

  public record LockedRecord(long activityId, String status, short format, Long version) {}

  public record GrowthState(long total, long currentStage) {}

  public record FinishedRecord(long version, Instant finishedAt) {}
}
