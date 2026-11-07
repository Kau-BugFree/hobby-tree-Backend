package com.hobbytree.hobbytree.domain.user.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "user_preferences")
@Check(
    constraints =
        "(budget_level IN ('LOW','MEDIUM','HIGH')) AND (available_time_minutes IN"
            + " (15,30,60,120)) AND (indoor_outdoor IN ('INDOOR','OUTDOOR','ANY')) AND"
            + " (difficulty_preference IN ('BEGINNER','INTERMEDIATE','ANY'))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPreference {
  public static UserPreference forUser(User user) {
    UserPreference preference = new UserPreference();
    preference.user = user;
    return preference;
  }

  public void updateNotificationFrequency(String frequency) {
    this.notificationFrequency = frequency;
  }

  // API-11: full replacement, not a partial merge - every field is always provided together.
  // preferencesVersion is bumped by the caller only when the new content actually differs.
  public void replacePreferences(
      String optionsVersion,
      String budgetLevel,
      int availableTimeMinutes,
      String indoorOutdoor,
      String difficultyPreference,
      String preferredCategoryIdsJson) {
    this.optionsVersion = optionsVersion;
    this.budgetLevel = budgetLevel;
    this.availableTimeMinutes = availableTimeMinutes;
    this.indoorOutdoor = indoorOutdoor;
    this.difficultyPreference = difficultyPreference;
    this.preferredCategoryIds = preferredCategoryIdsJson;
  }

  public void incrementPreferencesVersion() {
    this.preferencesVersion++;
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "preference_id", nullable = false)
  private Long preferenceId;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false, unique = true)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User user;

  @Column(name = "options_version", nullable = true, length = 40)
  private String optionsVersion;

  @Column(name = "budget_level", nullable = true, length = 10)
  private String budgetLevel;

  @Column(name = "available_time_minutes", nullable = true)
  private Integer availableTimeMinutes;

  @Column(name = "indoor_outdoor", nullable = true, length = 10)
  private String indoorOutdoor;

  @Column(name = "difficulty_preference", nullable = true, length = 20)
  private String difficultyPreference;

  // JSON array of 0~3 category ids (see HobbyCategoryCatalog), e.g. "[1,2]" - same jsonb
  // storage convention this table's previous preferred_hobbies/goals columns used.
  @JdbcTypeCode(SqlTypes.JSON)
  @ColumnDefault("'[]'::jsonb")
  @Column(name = "preferred_category_ids", nullable = false, columnDefinition = "jsonb")
  private String preferredCategoryIds = "[]";

  // 0 means "no preferences saved yet" - distinct from the row simply existing, since a row
  // can already exist here for a user who has only ever touched notificationFrequency.
  @ColumnDefault("0")
  @Column(name = "preferences_version", nullable = false)
  private int preferencesVersion = 0;

  @ColumnDefault("'NONE'")
  @Column(name = "notification_frequency", nullable = false, length = 30)
  private String notificationFrequency = "NONE";

  @ColumnDefault("now()")
  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
