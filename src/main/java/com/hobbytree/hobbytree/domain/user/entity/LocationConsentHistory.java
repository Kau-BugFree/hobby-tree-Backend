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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SourceType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "location_consent_history")
@Check(constraints = "(consent_action IN ('GRANTED','REVOKED'))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LocationConsentHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "location_consent_id", nullable = false)
  private Long locationConsentId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User user;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "consent_action", nullable = false, length = 20)
  private ConsentAction consentAction;

  @Column(name = "terms_version", nullable = false, length = 40)
  private String termsVersion;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "changed_at", nullable = false)
  private Instant changedAt;
}
