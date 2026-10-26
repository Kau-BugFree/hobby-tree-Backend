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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SourceType;
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
}
