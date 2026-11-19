package com.hobbytree.hobbytree.domain.activity.repository;

import com.hobbytree.hobbytree.domain.activity.dto.ActivityDetailResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ActivityRecordSnapshotRepository {
  private final JdbcTemplate jdbc;

  public boolean lockCatalog(long activityId) {
    var before = route(activityId);
    if (before.isEmpty()) return false;
    var ids = before.get();
    if (jdbc.queryForList(
                "SELECT hobby_id FROM hobbies WHERE hobby_id=? FOR SHARE",
                Long.class,
                ids.hobbyId())
            .isEmpty()
        || jdbc.queryForList(
                "SELECT hobby_stage_id FROM hobby_stages WHERE hobby_stage_id=? FOR SHARE",
                Long.class,
                ids.stageId())
            .isEmpty()
        || jdbc.queryForList(
                "SELECT activity_id FROM activities WHERE activity_id=? FOR SHARE",
                Long.class,
                activityId)
            .isEmpty()) return false;
    return route(activityId).filter(ids::equals).isPresent();
  }

  private Optional<Route> route(long activityId) {
    return jdbc
        .query(
            "SELECT hs.hobby_id,a.hobby_stage_id FROM activities a JOIN hobby_stages hs ON hs.hobby_stage_id=a.hobby_stage_id WHERE a.activity_id=?",
            (row, index) -> new Route(row.getLong(1), row.getLong(2)),
            activityId)
        .stream()
        .findFirst();
  }

  public void capture(
      long recordId, ActivityDetailResponse content, Instant capturedAt, String json) {
    for (var step : content.steps()) {
      jdbc.update(
          "INSERT INTO activity_record_steps(record_id,step_id,step_number,title,description,is_required,image_url) VALUES(?,?,?,?,?,?,?)",
          recordId,
          step.stepId(),
          step.stepNumber(),
          step.title(),
          step.description(),
          step.isRequired(),
          step.imageUrl());
    }
    jdbc.update(
        "INSERT INTO activity_record_contents(record_id,content_version,content_updated_at,captured_at,title,content_snapshot) VALUES(?,?,?,?,?,?::jsonb)",
        recordId,
        content.contentVersion(),
        content.contentUpdatedAt() == null ? null : Timestamp.from(content.contentUpdatedAt()),
        Timestamp.from(capturedAt),
        content.title(),
        json);
  }

  public Optional<Progress> progress(long recordId) {
    var versions =
        jdbc.queryForList(
            "SELECT content_version FROM activity_record_contents WHERE record_id=? AND snapshot_schema_version=1",
            Long.class,
            recordId);
    if (versions.isEmpty()) return Optional.empty();
    var steps =
        jdbc.query(
            "SELECT step_id,is_required,is_completed FROM activity_record_steps WHERE record_id=? ORDER BY step_number",
            (row, index) -> new Step(row.getLong(1), row.getBoolean(2), row.getBoolean(3)),
            recordId);
    long required = steps.stream().filter(Step::required).count();
    if (required == 0) return Optional.empty();
    long completed = steps.stream().filter(step -> step.required() && step.completed()).count();
    return Optional.of(
        new Progress(
            versions.getFirst(),
            steps.stream().filter(Step::completed).map(Step::id).toList(),
            (int) (100 * completed / required)));
  }

  private record Route(long hobbyId, long stageId) {}

  private record Step(long id, boolean required, boolean completed) {}

  public record Progress(long contentVersion, List<Long> completedStepIds, int percent) {}
}
