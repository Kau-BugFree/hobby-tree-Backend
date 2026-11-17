package com.hobbytree.hobbytree.domain.recommendation.repository;

import com.hobbytree.hobbytree.domain.recommendation.entity.Recommendation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
  boolean existsByRecommendationIdAndUser_UserId(Long recommendationId, Long userId);

  // API-15's "최근 추천 결과": createdAt DESC, id DESC as the tiebreak for same-instant rows.
  Optional<Recommendation> findFirstByUser_UserIdOrderByCreatedAtDescRecommendationIdDesc(
      Long userId);
}
