package com.hobbytree.hobbytree.domain.tree.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ChangeUserHobbyStatusRequest(
    @NotNull @Pattern(regexp = "ACTIVE|PAUSED|ENDED") String status) {}
