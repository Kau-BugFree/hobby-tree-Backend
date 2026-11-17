package com.hobbytree.hobbytree.domain.report.repository;

import com.hobbytree.hobbytree.domain.report.entity.Report;
import com.hobbytree.hobbytree.domain.report.entity.ReportReason;
import com.hobbytree.hobbytree.domain.report.entity.ReportTargetType;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {
  boolean existsByReporterUser_UserIdAndTargetTypeAndTargetIdAndReasonAndCreatedAtAfter(
      Long reporterUserId,
      ReportTargetType targetType,
      Long targetId,
      ReportReason reason,
      Instant after);
}
