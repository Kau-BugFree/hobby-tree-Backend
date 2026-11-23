package com.hobbytree.hobbytree.domain.itemrecommendation.service;

import com.hobbytree.hobbytree.domain.itemrecommendation.client.ItemRecommendationAiClient;
import com.hobbytree.hobbytree.domain.itemrecommendation.client.ItemRecommendationAiResponse;
import com.hobbytree.hobbytree.domain.itemrecommendation.dto.ItemRecommendationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// No outer transaction: the DB snapshot commits in ItemRecommendationSnapshot before the AI HTTP
// call starts, so a slow/unavailable AI server never holds a DB transaction open.
@Service
@RequiredArgsConstructor
public class ItemRecommendationService {
  private final ItemRecommendationSnapshot snapshot;
  private final ItemRecommendationAiClient ai;

  @Transactional(propagation = Propagation.NEVER)
  public ItemRecommendationAiResponse.Result recommend(
      Long userId, Long activityId, ItemRecommendationRequest request) {
    var aiRequest = snapshot.read(userId, activityId, request);
    return ai.recommend(aiRequest);
  }
}
