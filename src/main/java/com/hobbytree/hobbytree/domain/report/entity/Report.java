package com.hobbytree.hobbytree.domain.report.entity;

import com.hobbytree.hobbytree.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SourceType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "content_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "report_id", nullable = false)
  private Long reportId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "reporter_user_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User reporterUser;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "target_type", nullable = false, length = 20)
  private ReportTargetType targetType;

  @Column(name = "target_id", nullable = false)
  private Long targetId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "reason", nullable = false, length = 30)
  private ReportReason reason;

  @Column(name = "description", nullable = false, length = 1000)
  private String description;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @ColumnDefault("'RECEIVED'")
  @Column(name = "status", nullable = false, length = 20)
  private ReportStatus status = ReportStatus.RECEIVED;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  private Report(
      User reporterUser,
      ReportTargetType targetType,
      Long targetId,
      ReportReason reason,
      String description) {
    this.reporterUser = reporterUser;
    this.targetType = targetType;
    this.targetId = targetId;
    this.reason = reason;
    this.description = description;
    this.status = ReportStatus.RECEIVED;
  }

  public static Report of(
      User reporterUser,
      ReportTargetType targetType,
      Long targetId,
      ReportReason reason,
      String description) {
    return new Report(reporterUser, targetType, targetId, reason, description);
  }
}
