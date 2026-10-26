package com.hobbytree.hobbytree.domain.user.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "users",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"provider", "provider_user_id"})})
@Check(
    constraints =
        "(provider IN ('KAKAO','GOOGLE')) AND (user_role IN ('USER','ADMIN')) AND (account_status IN ('PENDING_ONBOARDING','ACTIVE','WITHDRAWN','SUSPENDED')) AND ((account_status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL) OR (account_status <> 'WITHDRAWN' AND withdrawn_at IS NULL))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "provider", nullable = false, length = 20)
  private AuthProvider provider;

  @Column(name = "provider_user_id", nullable = false, length = 255)
  private String providerUserId;

  @Column(name = "nickname", nullable = true, length = 50)
  private String nickname;

  @Column(name = "profile_image_key", nullable = true, columnDefinition = "text")
  private String profileImageKey;

  @Column(name = "age_range", nullable = true, length = 20)
  private String ageRange;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @ColumnDefault("'USER'")
  @Column(name = "user_role", nullable = false, length = 20)
  private UserRole userRole = UserRole.USER;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @ColumnDefault("'PENDING_ONBOARDING'")
  @Column(name = "account_status", nullable = false, length = 24)
  private AccountStatus accountStatus = AccountStatus.PENDING_ONBOARDING;

  @Column(name = "withdrawn_at", nullable = true)
  private Instant withdrawnAt;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @ColumnDefault("now()")
  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
