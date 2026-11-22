package com.hobbytree.hobbytree.domain.activity.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ActivityRecordDeletionRepository {
  private final JdbcTemplate jdbc;

  public Optional<Long> ownedTreeIncludingDeleted(long recordId, long userId) {
    return jdbc
        .queryForList(
            "SELECT r.user_hobby_id FROM activity_records r JOIN user_hobbies t USING(user_hobby_id) WHERE r.activity_record_id=? AND t.user_id=?",
            Long.class,
            recordId,
            userId)
        .stream()
        .findFirst();
  }

  public Optional<Record> lock(long recordId, long treeId) {
    return jdbc
        .query(
            "SELECT activity_id,activity_status,deleted_at FROM activity_records WHERE activity_record_id=? AND user_hobby_id=? FOR UPDATE",
            (row, i) ->
                new Record(
                    row.getLong(1),
                    row.getString(2),
                    row.getTimestamp(3) == null ? null : row.getTimestamp(3).toInstant()),
            recordId,
            treeId)
        .stream()
        .findFirst();
  }

  public Optional<String> receipt(long recordId) {
    return jdbc
        .queryForList(
            "SELECT response_payload::text FROM activity_record_deletions WHERE record_id=? AND schema_version=1",
            String.class,
            recordId)
        .stream()
        .findFirst();
  }

  /** Every live completed pair must be represented; historical snapshot absence is unrelated. */
  public boolean hasUnrepresentedCompletion(long treeId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM activity_records r WHERE r.user_hobby_id=?
          AND r.deleted_at IS NULL AND r.activity_status='COMPLETED'
          AND NOT EXISTS(SELECT 1 FROM activity_growth_contributions g
                         WHERE g.user_hobby_id=r.user_hobby_id AND g.activity_id=r.activity_id))
        """,
            Boolean.class,
            treeId));
  }

  public Optional<Credit> lockCredit(long treeId, long activityId) {
    return jdbc
        .query(
            "SELECT contribution_id,origin_record_id,COALESCE(progress_stage_number,credited_stage_number) FROM activity_growth_contributions WHERE user_hobby_id=? AND activity_id=? FOR UPDATE",
            (row, i) -> new Credit(row.getLong(1), row.getLong(2), row.getShort(3)),
            treeId,
            activityId)
        .stream()
        .findFirst();
  }

  public Optional<Long> remainingCompletion(long treeId, long activityId, long excluding) {
    return jdbc
        .queryForList(
            "SELECT activity_record_id FROM activity_records WHERE user_hobby_id=? AND activity_id=? AND activity_status='COMPLETED' AND deleted_at IS NULL AND activity_record_id<>? ORDER BY activity_record_id LIMIT 1",
            Long.class,
            treeId,
            activityId,
            excluding)
        .stream()
        .findFirst();
  }

  public void clearMirror(long recordId) {
    jdbc.update(
        "UPDATE activity_records SET growth_counted=false,growth_stage_number=NULL WHERE activity_record_id=? AND deleted_at IS NULL",
        recordId);
  }

  public void transfer(Credit credit, long newOrigin) {
    clearMirror(credit.origin());
    jdbc.update(
        "UPDATE activity_growth_contributions SET origin_record_id=? WHERE contribution_id=?",
        newOrigin,
        credit.id());
    jdbc.update(
        "UPDATE activity_records SET growth_counted=true,growth_stage_number=? WHERE activity_record_id=? AND deleted_at IS NULL",
        credit.stage(),
        newOrigin);
  }

  public void revoke(Credit credit) {
    jdbc.update("DELETE FROM activity_growth_contributions WHERE contribution_id=?", credit.id());
    clearMirror(credit.origin());
  }

  public void redistribute(long treeId, int perStage) {
    jdbc.update(
        """
        WITH buckets AS (
          SELECT contribution_id,LEAST(5,(row_number() OVER(ORDER BY contribution_id)-1)/?::bigint)::smallint AS stage
          FROM activity_growth_contributions WHERE user_hobby_id=?)
        UPDATE activity_growth_contributions g SET progress_stage_number=b.stage FROM buckets b
        WHERE g.contribution_id=b.contribution_id AND g.progress_stage_number IS DISTINCT FROM b.stage
        """,
        perStage,
        treeId);
    jdbc.update(
        """
        UPDATE activity_records r SET growth_stage_number=COALESCE(g.progress_stage_number,g.credited_stage_number)
        FROM activity_growth_contributions g WHERE g.user_hobby_id=? AND g.origin_record_id=r.activity_record_id
          AND r.deleted_at IS NULL AND r.growth_stage_number IS DISTINCT FROM COALESCE(g.progress_stage_number,g.credited_stage_number)
        """,
        treeId);
  }

  public Instant markDeleted(long recordId) {
    return jdbc.queryForObject(
        """
        WITH stamp AS MATERIALIZED (SELECT GREATEST(clock_timestamp(),started_at,finished_at,updated_at+interval '1 microsecond') AS at
                                   FROM activity_records WHERE activity_record_id=?)
        UPDATE activity_records r SET deleted_at=stamp.at,updated_at=stamp.at FROM stamp
        WHERE r.activity_record_id=? AND r.deleted_at IS NULL RETURNING r.deleted_at
        """,
        (row, i) -> row.getTimestamp(1).toInstant(),
        recordId,
        recordId);
  }

  public String audit(long treeId) {
    return jdbc.queryForObject(
        """
        SELECT jsonb_build_object('treeStage',t.current_stage_number,
          'contributions',COALESCE((SELECT jsonb_agg(to_jsonb(g) ORDER BY g.contribution_id)
                          FROM activity_growth_contributions g WHERE g.user_hobby_id=t.user_hobby_id),'[]'::jsonb),
          'mirrors',COALESCE((SELECT jsonb_agg(jsonb_build_object('recordId',r.activity_record_id,'activityId',r.activity_id,
                                    'growthCounted',r.growth_counted,'growthStage',r.growth_stage_number,'deletedAt',r.deleted_at)
                                    ORDER BY r.activity_record_id)
                            FROM activity_records r WHERE r.user_hobby_id=t.user_hobby_id),'[]'::jsonb))::text
        FROM user_hobbies t WHERE t.user_hobby_id=?
        """,
        String.class,
        treeId);
  }

  public void save(
      long recordId, Instant at, String action, String before, String after, String response) {
    jdbc.update(
        "INSERT INTO activity_record_deletions(record_id,deleted_at,growth_action,growth_before,growth_after,response_payload) VALUES(?,?,?,?::jsonb,?::jsonb,?::jsonb)",
        recordId,
        Timestamp.from(at),
        action,
        before,
        after,
        response);
  }

  public record Record(long activityId, String status, Instant deletedAt) {}

  public record Credit(long id, long origin, short stage) {}
}
