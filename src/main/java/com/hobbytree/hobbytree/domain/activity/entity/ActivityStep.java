package com.hobbytree.hobbytree.domain.activity.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(
    name = "activity_steps",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"activity_id", "step_number"})})
@Check(constraints = "(step_number > 0)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActivityStep {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "activity_step_id", nullable = false)
  private Long activityStepId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "activity_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Activity activity;

  @Column(name = "step_number", nullable = false)
  private Short stepNumber;

  @Column(name = "instruction", nullable = false, columnDefinition = "text")
  private String instruction;
}
