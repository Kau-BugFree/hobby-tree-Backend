package com.hobbytree.hobbytree.domain.tree.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only credit identity. Historical records remain untouched and own their original IDs. */
@Entity
@Table(name = "activity_growth_contributions")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActivityGrowthContribution {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "contribution_id", nullable = false)
  private Long contributionId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_hobby_id", nullable = false)
  private UserHobby userHobby;

  @Column(name = "activity_id", nullable = false)
  private Long activityId;

  @Column(name = "origin_record_id", nullable = false)
  private Long originRecordId;

  @Column(name = "credited_stage_number", nullable = false)
  private Short creditedStageNumber;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "source", nullable = false, length = 20)
  private GrowthContributionSource source;

  @Column(name = "credited_at")
  private Instant creditedAt;

  @Column(name = "migrated_at")
  private Instant migratedAt;
}
