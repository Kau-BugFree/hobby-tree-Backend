package com.hobbytree.hobbytree.domain.itemrecommendation.client;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ItemRecommendationAiProperties.class)
public class ItemRecommendationAiConfig {}
