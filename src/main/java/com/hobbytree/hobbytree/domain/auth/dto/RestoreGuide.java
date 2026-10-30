package com.hobbytree.hobbytree.domain.auth.dto;

import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import java.time.Instant;

public record RestoreGuide(
    AccountStatus memberStatus,
    boolean restorable,
    Instant restorableUntil,
    String restoreToken,
    long restoreTokenExpiresIn) {
  @Override
  public String toString() {
    return "RestoreGuide[restorable=" + restorable + ", restoreToken=[redacted]]";
  }
}
