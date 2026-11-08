package com.hobbytree.hobbytree.domain.tree.entity;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "activity_records",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"user_hobby_id", "activity_id"})})
@Check(
    constraints =
        "(activity_status IN ('IN_PROGRESS','COMPLETED','PARTIAL','NOT_DONE')) AND (attempt_count > 0) AND (growth_stage_number BETWEEN 0 AND 5) AND (satisfaction BETWEEN 1 AND 5) AND (actual_minutes >= 0) AND (growth_stage_number IS NULL OR growth_counted) AND (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at) AND (NOT growth_counted OR (activity_status='COMPLETED' AND growth_stage_number IS NOT NULL))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActivityRecord {

  // API-39: credits a brand-new record for an activity this tree never attempted before.
  // growthStageNumber is the hobby's CURRENT stage at credit time - countGrowthByTreeIds
  // only counts a record toward "progress at the current stage" when the two match, so
  // crediting at any other value would silently never count here.
  public static ActivityRecord completeFromExperience(
      UserHobby userHobby, Activity activity, Instant finishedAt, short growthStageNumber) {
    ActivityRecord record = new ActivityRecord();
    record.userHobby = userHobby;
    record.activity = activity;
    record.activityStatus = ActivityStatus.COMPLETED;
    record.growthCounted = true;
    record.growthStageNumber = growthStageNumber;
    record.startedAt = finishedAt;
    record.finishedAt = finishedAt;
    return record;
  }

  // API-39: credits an EXISTING record - e.g. one left IN_PROGRESS by a self-tracked attempt
  // ("자체 진행 중인 활동 기록은 유지한다") - without resetting its stepProgress/attemptCount.
  // Only called when growthCounted was still false; a record whose growth was already counted
  // (from either source) is reused as-is instead; see ExperienceAttendanceService.
  public void completeFromExperience(Instant finishedAt, short growthStageNumber) {
    this.activityStatus = ActivityStatus.COMPLETED;
    this.growthCounted = true;
    this.growthStageNumber = growthStageNumber;
    this.finishedAt = finishedAt;
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "activity_record_id", nullable = false)
  private Long activityRecordId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_hobby_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private UserHobby userHobby;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "activity_id", nullable = false)
  private Activity activity;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @ColumnDefault("'IN_PROGRESS'")
  @Column(name = "activity_status", nullable = false, length = 20)
  private ActivityStatus activityStatus = ActivityStatus.IN_PROGRESS;

  @JdbcTypeCode(SqlTypes.JSON)
  @ColumnDefault("'{}'::jsonb")
  @Column(name = "step_progress", nullable = false, columnDefinition = "jsonb")
  private String stepProgress = "{}";

  @ColumnDefault("1")
  @Column(name = "attempt_count", nullable = false)
  private Integer attemptCount = 1;

  @ColumnDefault("FALSE")
  @Column(name = "growth_counted", nullable = false)
  private Boolean growthCounted = false;

  @Column(name = "growth_stage_number", nullable = true)
  private Short growthStageNumber;

  @Column(name = "started_at", nullable = true)
  private Instant startedAt;

  @Column(name = "last_resumed_at", nullable = true)
  private Instant lastResumedAt;

  @Column(name = "finished_at", nullable = true)
  private Instant finishedAt;

  @Column(name = "satisfaction", nullable = true)
  private Short satisfaction;

  @Column(name = "perceived_difficulty", nullable = true, length = 20)
  private String perceivedDifficulty;

  @Column(name = "actual_minutes", nullable = true)
  private Integer actualMinutes;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @ColumnDefault("now()")
  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
