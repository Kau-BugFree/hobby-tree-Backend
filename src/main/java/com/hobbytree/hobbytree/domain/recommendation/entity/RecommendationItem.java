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
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

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

  // Takes the already-loaded Hobby/Activity the caller built this candidate from - no extra
  // lookup, no lazy-loading risk since none of these are re-read after this transaction.
  public static RecommendationItem of(
      Recommendation recommendation,
      Hobby hobby,
      Activity firstActivity,
      short rankOrder,
      BigDecimal score,
      String recommendationReason,
      String matchedCriteria) {
    RecommendationItem item = new RecommendationItem();
    item.recommendation = recommendation;
    item.hobby = hobby;
    item.firstActivity = firstActivity;
    item.rankOrder = rankOrder;
    item.score = score;
    item.recommendationReason = recommendationReason;
    item.matchedCriteria = matchedCriteria;
    return item;
  }

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

  // JSON array of tags, e.g. "[\"WITHIN_BUDGET\",\"BEGINNER_FRIENDLY\"]" - API-15 (최근 추천
  // 결과 조회) echoes this straight back from storage rather than recomputing it against
  // whatever the hobby/preferences look like now.
  @JdbcTypeCode(SqlTypes.JSON)
  @ColumnDefault("'[]'::jsonb")
  @Column(name = "matched_criteria", nullable = false, columnDefinition = "jsonb")
  private String matchedCriteria = "[]";

  @Column(name = "selected_at", nullable = true)
  private Instant selectedAt;
}
