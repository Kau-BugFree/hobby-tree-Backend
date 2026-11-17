package com.hobbytree.hobbytree.domain.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record ChatLaunchSubscriptionResponse(
    String subscriptionType,
    @JsonProperty("isSubscribed") boolean isSubscribed,
    String channel,
    Instant subscribedAt) {}
