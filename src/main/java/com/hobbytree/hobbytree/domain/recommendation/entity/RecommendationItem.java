package com.hobbytree.hobbytree.domain.recommendation.entity;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import com.hobbytree.hobbytree.domain.hobby.entity.Hobby;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(
    name = "recommendation_items",
    uniqueConstraints = {
      @UniqueConstraint(columnNames = {"recommendation_id", "rank_order"}),
      @UniqueConstraint(columnNames = {"recommendation_id", "hobby_id"})
    })
@Check(constraints = "(rank_order > 0)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendationItem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "recommendation_item_id", nullable = false)
  private Long recommendationItemId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "recommendation_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Recommendation recommendation;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "hobby_id", nullable = false)
  private Hobby hobby;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "first_activity_id", nullable = true)
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private Activity firstActivity;

  @Column(name = "rank_order", nullable = false)
  private Short rankOrder;

  @Column(name = "score", nullable = true, precision = 5, scale = 2)
  private BigDecimal score;

  @Column(name = "recommendation_reason", nullable = false, columnDefinition = "text")
  private String recommendationReason;

  @Column(name = "selected_at", nullable = true)
  private Instant selectedAt;
}
