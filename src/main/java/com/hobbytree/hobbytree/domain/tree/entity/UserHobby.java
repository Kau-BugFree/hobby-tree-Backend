package com.hobbytree.hobbytree.domain.tree.entity;

import com.hobbytree.hobbytree.domain.hobby.entity.Hobby;
import com.hobbytree.hobbytree.domain.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SourceType;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "user_hobbies",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"user_id", "hobby_id"})})
@Check(
    constraints =
        "(tree_status IN ('ACTIVE','PAUSED','ENDED')) AND (current_stage_number BETWEEN 0 AND 5)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserHobby {
  public static UserHobby plant(User user, Hobby hobby) {
    var tree = new UserHobby();
    tree.user = user;
    tree.hobby = hobby;
    return tree;
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "user_hobby_id", nullable = false)
  private Long userHobbyId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User user;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "hobby_id", nullable = false)
  private Hobby hobby;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @ColumnDefault("'ACTIVE'")
  @Column(name = "tree_status", nullable = false, length = 20)
  private TreeStatus treeStatus = TreeStatus.ACTIVE;

  @ColumnDefault("0")
  @Column(name = "current_stage_number", nullable = false)
  private Short currentStageNumber = (short) 0;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "planted_at", nullable = false)
  private Instant plantedAt;

  @Column(name = "paused_at", nullable = true)
  private Instant pausedAt;

  @Column(name = "ended_at", nullable = true)
  private Instant endedAt;

  // V13 owns generation on both INSERT and actual row changes, including non-JPA writes.
  @Generated(event = {EventType.INSERT, EventType.UPDATE})
  @ColumnDefault("clock_timestamp()")
  @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
  private Instant updatedAt;

  // V1 defines stage 5 as terminal (this entity's own CHECK constraint), independent of which
  // catalog rows have been seeded - same constant UserHobbyService.MAX_STAGE_NUMBER uses.
  private static final short MAX_STAGE_NUMBER = 5;

  /**
   * Advances exactly one stage once a newly-credited activity brings the current stage's count up
   * to the hobby's per-stage requirement. Returns whether it moved.
   */
  public boolean advanceStageIfThresholdMet(
      long atCurrentStageCountAfterCredit, int activitiesPerStage) {
    if (currentStageNumber >= MAX_STAGE_NUMBER
        || atCurrentStageCountAfterCredit < activitiesPerStage) return false;
    currentStageNumber++;
    return true;
  }

  /** Updates the current lifecycle only; growth and activity history remain unchanged. */
  public void changeStatus(TreeStatus status, Instant changedAt) {
    if (treeStatus == status) return;
    treeStatus = status;
    switch (status) {
      case ACTIVE -> {
        pausedAt = null;
        endedAt = null;
      }
      case PAUSED -> {
        pausedAt = changedAt;
        endedAt = null;
      }
      case ENDED -> {
        pausedAt = null;
        endedAt = changedAt;
      }
    }
  }
}
