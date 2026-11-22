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
    return snapshot(recordId).map(Snapshot::progress);
  }

  public Optional<Snapshot> snapshot(long recordId) {
    var contents =
        jdbc.query(
            "SELECT content_version,title FROM activity_record_contents WHERE record_id=? AND snapshot_schema_version=1",
            (row, index) -> new Content(row.getLong(1), row.getString(2)),
            recordId);
    if (contents.isEmpty()) return Optional.empty();
    var steps =
        jdbc.query(
            "SELECT step_id,step_number,title,description,is_required,image_url,is_completed FROM activity_record_steps WHERE record_id=? ORDER BY step_number,step_id",
            (row, index) ->
                new Step(
                    new ActivityDetailResponse.Step(
                        row.getLong(1),
                        row.getShort(2),
                        row.getString(3),
                        row.getString(4),
                        row.getBoolean(5),
                        row.getString(6)),
                    row.getBoolean(7)),
            recordId);
    long required = steps.stream().filter(step -> step.content().isRequired()).count();
    if (required == 0) return Optional.empty();
    long completed =
        steps.stream().filter(step -> step.content().isRequired() && step.completed()).count();
    var progress =
        new Progress(
            contents.getFirst().version(),
            steps.stream().filter(Step::completed).map(step -> step.content().stepId()).toList(),
            (int) (100 * completed / required));
    return Optional.of(
        new Snapshot(
            contents.getFirst().title(), progress, steps.stream().map(Step::content).toList()));
  }

  private record Route(long hobbyId, long stageId) {}

  private record Content(long version, String title) {}

  private record Step(ActivityDetailResponse.Step content, boolean completed) {}

  public record Snapshot(
      String title, Progress progress, List<ActivityDetailResponse.Step> steps) {}

  public record Progress(long contentVersion, List<Long> completedStepIds, int percent) {}
}
