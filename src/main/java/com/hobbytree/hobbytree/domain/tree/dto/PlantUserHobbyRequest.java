package com.hobbytree.hobbytree.domain.tree.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PlantUserHobbyRequest(
    @NotNull @Positive Long hobbyId, @Positive Long recommendationId) {}
