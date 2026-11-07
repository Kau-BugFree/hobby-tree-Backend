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
    uniqueConstraints = {@UniqueConstraint(columnNames = {"session_id", "user_id"})})
@Check(
    constraints =
        "(reservation_status IN ('REQUESTED','REDIRECTED','PENDING_VERIFICATION','CONFIRMED','CANCELLED','ATTENDED')) AND (party_size BETWEEN 1 AND 4) AND (total_amount >= 0) AND (attendance_verified_at IS NULL OR reservation_status='ATTENDED') AND (credited_activity_record_id IS NULL OR attendance_verified_at IS NOT NULL)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExperienceReservation {

  // API-37: 외부 예약 이동 이력 또는 제출한 외부 예약 참조만 저장한다. externalReservationId만
  // 있으면 PENDING_VERIFICATION, 없으면 REDIRECTED로 생성 - 둘 다 서버가 아직 소유권을
  // 확인하지 않은 상태다.
  public static ExperienceReservation of(
      String reservationCode,
      Experience experience,
      Long sessionId,
      User user,
      Long providerId,
      String externalBookingRef) {
    ExperienceReservation reservation = new ExperienceReservation();
    reservation.reservationCode = reservationCode;
    reservation.experience = experience;
    reservation.sessionId = sessionId;
    reservation.user = user;
    reservation.providerId = providerId;
    reservation.externalBookingRef = externalBookingRef;
    reservation.reservationStatus =
        externalBookingRef == null
            ? ReservationStatus.REDIRECTED
            : ReservationStatus.PENDING_VERIFICATION;
    return reservation;
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "reservation_id", nullable = false)
  private Long reservationId;

  @Column(name = "reservation_code", nullable = false, length = 60, unique = true)
  private String reservationCode;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "experience_id", nullable = false)
  private Experience experience;

  // Plain FK, not a @ManyToOne: ExperienceReservationService only ever needs the id back
  // (never traverses to the session's own fields through this entity) - same reasoning as
  // ExperienceSession.experienceId.
  // Nullable only for legacy reservations whose session cannot be identified.
  @Column(name = "session_id", nullable = true)
  private Long sessionId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User user;

  // Denormalized from the experience at creation time, not a live join - see V19's own
  // comment for why the provider-scoped external-reference uniqueness needs this.
  @Column(name = "provider_id", nullable = true)
  private Long providerId;

  // No DB/Java default: of() always sets this explicitly (REDIRECTED or PENDING_VERIFICATION),
  // and nothing else in this codebase creates a row without going through that factory.
  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "reservation_status", nullable = false, length = 24)
  private ReservationStatus reservationStatus;

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
