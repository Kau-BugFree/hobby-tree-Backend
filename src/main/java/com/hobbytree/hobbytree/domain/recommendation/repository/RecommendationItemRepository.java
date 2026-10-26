package com.hobbytree.hobbytree.domain.recommendation.repository;

import com.hobbytree.hobbytree.domain.recommendation.entity.RecommendationItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationItemRepository extends JpaRepository<RecommendationItem, Long> {}
