package com.hobbytree.hobbytree.domain.user.dto;

import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import java.time.Instant;

public record WithdrawResponse(
    Long userId, AccountStatus memberStatus, Instant withdrawnAt, Instant restorableUntil) {}
