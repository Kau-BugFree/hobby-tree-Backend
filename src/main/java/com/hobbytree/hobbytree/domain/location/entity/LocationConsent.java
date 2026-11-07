package com.hobbytree.hobbytree.domain.location.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.type.SqlTypes;

// Append-only history (API-43 writes rows here; API-42 only reads the latest one per
// user+consentType to gate the DEVICE path). userId is a plain column for the same reason as
// UserLocation - nothing here ever needs to navigate to the User entity. No mutator: a new
// decision is always a new row, never an edit of an existing one.
@Entity
@Table(name = "location_consents")
@Check(constraints = "(consent_type IN ('NEARBY_EXPERIENCES'))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LocationConsent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "location_consent_id", nullable = false)
  private Long locationConsentId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "consent_type", nullable = false, length = 30)
  private LocationConsentType consentType;

  @Column(name = "version", nullable = false, length = 40)
  private String version;

  @Column(name = "agreed", nullable = false)
  private boolean agreed;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt;

  private LocationConsent(
      Long userId, LocationConsentType consentType, String version, boolean agreed) {
    this.userId = userId;
    this.consentType = consentType;
    this.version = version;
    this.agreed = agreed;
  }

  public static LocationConsent of(
      Long userId, LocationConsentType consentType, String version, boolean agreed) {
    return new LocationConsent(userId, consentType, version, agreed);
  }
}
