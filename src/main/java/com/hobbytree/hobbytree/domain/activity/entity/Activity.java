package com.hobbytree.hobbytree.domain.activity.entity;

import com.hobbytree.hobbytree.domain.hobby.entity.HobbyStage;
import com.hobbytree.hobbytree.domain.user.entity.User;
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
@Table(name = "activities")
@Check(
    constraints =
        "(expected_active_minutes >= 0) AND (expected_total_minutes >= 0) AND (estimated_cost_min >= 0) AND (estimated_cost_max >= 0) AND (review_status IN ('DRAFT','REVIEW','PUBLISHED','REJECTED')) AND (estimated_cost_max IS NULL OR estimated_cost_min IS NULL OR estimated_cost_max >= estimated_cost_min)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Activity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "activity_id", nullable = false)
  private Long activityId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "hobby_stage_id", nullable = false)
  private HobbyStage hobbyStage;

  @Column(name = "activity_code", nullable = false, length = 80, unique = true)
  private String activityCode;

  @Column(name = "activity_name", nullable = false, length = 160)
  private String activityName;

  @Column(name = "category", nullable = true, length = 80)
  private String category;

  @Column(name = "short_description", nullable = true, length = 500)
  private String shortDescription;

  @Column(name = "detailed_description", nullable = true, columnDefinition = "text")
  private String detailedDescription;

  @Column(name = "expected_active_minutes", nullable = true)
  private Integer expectedActiveMinutes;

  @Column(name = "expected_total_minutes", nullable = true)
  private Integer expectedTotalMinutes;

  @Column(name = "estimated_cost_min", nullable = true)
  private Integer estimatedCostMin;

  @Column(name = "estimated_cost_max", nullable = true)
  private Integer estimatedCostMax;

  @Column(name = "difficulty", nullable = true, length = 30)
  private String difficulty;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "required_conditions", nullable = true, columnDefinition = "jsonb")
  private String requiredConditions;

  @Column(name = "safety_notes", nullable = true, columnDefinition = "text")
  private String safetyNotes;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @ColumnDefault("'DRAFT'")
  @Column(name = "review_status", nullable = false, length = 20)
  private ReviewStatus reviewStatus = ReviewStatus.DRAFT;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "created_by_user_id", nullable = true)
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private User createdByUser;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "reviewed_by_user_id", nullable = true)
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private User reviewedByUser;

  @Column(name = "reviewed_at", nullable = true)
  private Instant reviewedAt;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @ColumnDefault("now()")
  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
