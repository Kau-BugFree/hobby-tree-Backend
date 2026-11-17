package com.hobbytree.hobbytree.domain.user.dto;

import com.hobbytree.hobbytree.domain.auth.dto.SocialLoginRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.annotation.JsonDeserialize;

public record UpdateNotificationSettingsRequest(
    @NotNull
        @Pattern(regexp = "NONE|WEEKLY|ACTIVITY_DAYS")
        @JsonDeserialize(using = SocialLoginRequest.TokenDeserializer.class)
        String notificationFrequency) {}
