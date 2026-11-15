package com.hobbytree.hobbytree.domain.onboarding.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

// Append-only agreement history (API-13 writes one row per decision submitted). userId is a
// plain column for the same ec2-profile lazy-loading reason as UserLocation/LocationConsent -
// nothing here ever needs to navigate to the User entity. termsId references
// OnboardingService.TERMS's static list, not a DB table, so there's no FK for it.
@Entity
@Table(name = "user_terms_agreements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserTermsAgreement {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "user_terms_agreement_id", nullable = false)
  private Long userTermsAgreementId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "terms_id", nullable = false)
  private int termsId;

  @Column(name = "version", nullable = false, length = 40)
  private String version;

  @Column(name = "agreed", nullable = false)
  private boolean agreed;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt;

  private UserTermsAgreement(Long userId, int termsId, String version, boolean agreed) {
    this.userId = userId;
    this.termsId = termsId;
    this.version = version;
    this.agreed = agreed;
  }

  public static UserTermsAgreement of(Long userId, int termsId, String version, boolean agreed) {
    return new UserTermsAgreement(userId, termsId, version, agreed);
  }
}
