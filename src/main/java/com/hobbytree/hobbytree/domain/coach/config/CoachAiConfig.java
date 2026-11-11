package com.hobbytree.hobbytree.domain.coach.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoachAiProperties.class)
public class CoachAiConfig {}
