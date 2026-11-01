package com.hobbytree.hobbytree.domain.user.dto;

public record NotificationSettingsResponse(
    boolean activityReminderEnabled,
    boolean treeGrowthEnabled,
    boolean experienceOpenEnabled,
    boolean announcementEnabled,
    QuietHoursResponse quietHours) {

  // No notification-settings storage exists yet (API-46 is not implemented), so every member
  // currently sees these API.md-documented defaults rather than a persisted, per-user value.
  public static NotificationSettingsResponse defaults() {
    return new NotificationSettingsResponse(
        true, true, true, true, new QuietHoursResponse(false, "22:00", "08:00", "Asia/Seoul"));
  }
}
