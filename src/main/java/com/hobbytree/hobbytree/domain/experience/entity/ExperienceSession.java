package com.hobbytree.hobbytree.domain.experience.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

// A class/모임 can recur across multiple scheduled occurrences; API-35's list shows/sorts by
// the soonest upcoming one per experience ("다음 일정"). experienceId is a plain FK, not a
// @ManyToOne - ExperienceService only ever batches these by a list of ids, never traverses
// the association, so an association would just be another lazy field to avoid under the ec2
// profile's open-in-view:false.
@Entity
@Table(name = "experience_sessions")
@Check(constraints = "(duration_minutes > 0)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExperienceSession {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "session_id", nullable = false)
  private Long sessionId;

  @Column(name = "experience_id", nullable = false)
  private Long experienceId;

  @Column(name = "starts_at", nullable = false)
  private Instant startsAt;

  @Column(name = "duration_minutes", nullable = false)
  private Integer durationMinutes;

  // Nullable like experiences.capacity itself - no cap recorded means no limit. API-37 hasn't
  // shipped per-session booking yet (experience_reservations has no session reference), so
  // API-36's remainingSeats is this capacity as-is, not reduced by any reservation count -
  // revisit once API-37 wires real bookings to a specific session.
  @Column(name = "capacity", nullable = true)
  private Integer capacity;
}
