package com.hobbytree.hobbytree.domain.experience.entity;

import com.hobbytree.hobbytree.domain.tree.entity.ActivityRecord;
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
@Table(
    name = "experience_reservations",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"experience_id", "user_id"})})
@Check(
    constraints =
        "(reservation_status IN ('REQUESTED','CONFIRMED','CANCELLED','ATTENDED')) AND (party_size BETWEEN 1 AND 4) AND (total_amount >= 0) AND (attendance_verified_at IS NULL OR reservation_status='ATTENDED') AND (credited_activity_record_id IS NULL OR attendance_verified_at IS NOT NULL)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExperienceReservation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "reservation_id", nullable = false)
  private Long reservationId;

  @Column(name = "reservation_code", nullable = false, length = 60, unique = true)
  private String reservationCode;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "experience_id", nullable = false)
  private Experience experience;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User user;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @ColumnDefault("'REQUESTED'")
  @Column(name = "reservation_status", nullable = false, length = 24)
  private ReservationStatus reservationStatus = ReservationStatus.REQUESTED;

  @ColumnDefault("1")
  @Column(name = "party_size", nullable = false)
  private Short partySize = (short) 1;

  @Column(name = "total_amount", nullable = true)
  private Integer totalAmount;

  @Column(name = "payment_method_label", nullable = true, length = 40)
  private String paymentMethodLabel;

  @Column(name = "external_booking_ref", nullable = true, length = 160)
  private String externalBookingRef;

  @Column(name = "attendance_verified_at", nullable = true)
  private Instant attendanceVerifiedAt;

  @OneToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "credited_activity_record_id", nullable = true, unique = true)
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private ActivityRecord creditedActivityRecord;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "booked_at", nullable = false)
  private Instant bookedAt;

  @ColumnDefault("now()")
  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
