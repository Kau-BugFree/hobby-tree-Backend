package com.hobbytree.hobbytree.domain.recommendation.entity;

import com.hobbytree.hobbytree.domain.user.entity.User;
import jakarta.persistence.*;
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
@Table(name = "recommendations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recommendation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "recommendation_id", nullable = false)
  private Long recommendationId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User user;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "preference_snapshot", nullable = false, columnDefinition = "jsonb")
  private String preferenceSnapshot;

  @Column(name = "recommendation_version", nullable = true, length = 80)
  private String recommendationVersion;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
