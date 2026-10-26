package com.hobbytree.hobbytree.domain.recommendation.repository;

import com.hobbytree.hobbytree.domain.recommendation.entity.Recommendation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {}
