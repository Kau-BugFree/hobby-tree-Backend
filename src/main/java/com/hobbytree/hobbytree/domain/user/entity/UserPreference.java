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
        "(first_activity_budget BETWEEN 0 AND 1000000) AND (first_budget_status IN ('SET','DECIDE_LATER','UNKNOWN')) AND (monthly_budget BETWEEN 0 AND 3000000) AND (session_minutes BETWEEN 10 AND 480) AND ((first_budget_status = 'SET' AND first_activity_budget IS NOT NULL) OR (first_budget_status <> 'SET' AND first_activity_budget IS NULL))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPreference {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "preference_id", nullable = false)
  private Long preferenceId;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false, unique = true)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User user;

  @Column(name = "hobby_status", nullable = true, length = 40)
  private String hobbyStatus;

  @Column(name = "first_activity_budget", nullable = true)
  private Integer firstActivityBudget;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @ColumnDefault("'UNKNOWN'")
  @Column(name = "first_budget_status", nullable = false, length = 20)
  private FirstBudgetStatus firstBudgetStatus = FirstBudgetStatus.UNKNOWN;

  @Column(name = "monthly_budget", nullable = true)
  private Integer monthlyBudget;

  @Column(name = "session_minutes", nullable = true)
  private Integer sessionMinutes;

  @Column(name = "weekly_frequency", nullable = true, length = 40)
  private String weeklyFrequency;

  @Column(name = "preferred_environment", nullable = true, length = 40)
  private String preferredEnvironment;

  @Column(name = "companion_preference", nullable = true, length = 40)
  private String companionPreference;

  @Column(name = "activity_selection_style", nullable = true, length = 40)
  private String activitySelectionStyle;

  @Column(name = "record_style", nullable = true, length = 40)
  private String recordStyle;

  @ColumnDefault("'NONE'")
  @Column(name = "notification_frequency", nullable = false, length = 30)
  private String notificationFrequency = "NONE";

  @JdbcTypeCode(SqlTypes.JSON)
  @ColumnDefault("'[]'::jsonb")
  @Column(name = "preferred_hobbies", nullable = false, columnDefinition = "jsonb")
  private String preferredHobbies = "[]";

  @JdbcTypeCode(SqlTypes.JSON)
  @ColumnDefault("'[]'::jsonb")
  @Column(name = "goals", nullable = false, columnDefinition = "jsonb")
  private String goals = "[]";

  @JdbcTypeCode(SqlTypes.JSON)
  @ColumnDefault("'[]'::jsonb")
  @Column(name = "owned_assets", nullable = false, columnDefinition = "jsonb")
  private String ownedAssets = "[]";

  @JdbcTypeCode(SqlTypes.JSON)
  @ColumnDefault("'[]'::jsonb")
  @Column(name = "exclusions", nullable = false, columnDefinition = "jsonb")
  private String exclusions = "[]";

  @JdbcTypeCode(SqlTypes.JSON)
  @ColumnDefault("'{}'::jsonb")
  @Column(name = "hobby_specific_answers", nullable = false, columnDefinition = "jsonb")
  private String hobbySpecificAnswers = "{}";

  @Column(name = "custom_interest_text", nullable = true, length = 200)
  private String customInterestText;

  @Column(name = "additional_consideration", nullable = true, columnDefinition = "text")
  private String additionalConsideration;

  @ColumnDefault("now()")
  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
