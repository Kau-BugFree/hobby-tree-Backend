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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

// userId/regionId are plain columns, not @ManyToOne associations: this service never needs to
// navigate from here to the User or Region entity (the caller already has userId, and the
// Region is always looked up separately via RegionRepository to validate its level before
// saving) - see the ec2-profile lazy-loading lesson from HobbyService/SponsoredContentService.
@Entity
@Table(name = "user_locations")
@Check(constraints = "(source IN ('MANUAL','DEVICE'))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserLocation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "user_location_id", nullable = false)
  private Long userLocationId;

  @Column(name = "user_id", nullable = false, unique = true)
  private Long userId;

  @Column(name = "region_id", nullable = false)
  private Long regionId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "source", nullable = false, length = 10)
  private LocationSource source;

  @ColumnDefault("now()")
  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  private UserLocation(Long userId, Long regionId, LocationSource source) {
    this.userId = userId;
    this.regionId = regionId;
    this.source = source;
  }

  public static UserLocation of(Long userId, Long regionId, LocationSource source) {
    return new UserLocation(userId, regionId, source);
  }

  // "회원의 활동 지역을 전체 교체" (API-42 step 3): a full replace, not a partial merge.
  public void replace(Long regionId, LocationSource source) {
    this.regionId = regionId;
    this.source = source;
  }
}
