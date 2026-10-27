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
@Table(name = "user_locations")
@Check(constraints = "(location_status IN ('ACTIVE','REVOKED'))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserLocation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "user_location_id", nullable = false)
  private Long userLocationId;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false, unique = true)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User user;

  @Column(name = "region_sido", nullable = false, length = 50)
  private String regionSido;

  @Column(name = "region_sigungu", nullable = false, length = 50)
  private String regionSigungu;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @ColumnDefault("'ACTIVE'")
  @Column(name = "location_status", nullable = false, length = 20)
  private LocationStatus locationStatus = LocationStatus.ACTIVE;

  @ColumnDefault("now()")
  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
