package com.hobbytree.hobbytree.domain.recommendation.client;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecommendationAiProperties.class)
public class RecommendationAiConfig {}
