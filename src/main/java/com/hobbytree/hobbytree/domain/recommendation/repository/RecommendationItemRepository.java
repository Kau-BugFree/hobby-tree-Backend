package com.hobbytree.hobbytree.domain.recommendation.repository;

import com.hobbytree.hobbytree.domain.recommendation.entity.RecommendationItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendationItemRepository extends JpaRepository<RecommendationItem, Long> {
  boolean existsByRecommendation_RecommendationIdAndHobby_HobbyId(
      Long recommendationId, Long hobbyId);

  // JOIN FETCH hobby: API-15 reads hobby.isSupported/hobbyName/thumbnailUrl right after this
  // call returns, which would otherwise be a LazyInitializationException under the ec2
  // profile's open-in-view:false (same reasoning as HobbyService's own fetch-joins).
  @Query(
      """
      SELECT ri FROM RecommendationItem ri
      JOIN FETCH ri.hobby
      WHERE ri.recommendation.recommendationId = :recommendationId
      ORDER BY ri.rankOrder ASC
      """)
  List<RecommendationItem> findByRecommendationIdWithHobby(
      @Param("recommendationId") Long recommendationId);
}
