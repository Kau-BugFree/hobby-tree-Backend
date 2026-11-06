package com.hobbytree.hobbytree.domain.region.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// region_id is the real 법정동코드 value (seeded by V8), not a generated surrogate key -
// see V8's own comment for why. parentRegionId is a plain column, not a @ManyToOne self-join:
// the API only ever needs the raw id back (never the parent's name/level), so mapping it as an
// association would just be another lazy field to accidentally dereference later.
@Entity
@Table(name = "regions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Region {

  @Id
  @Column(name = "region_id", nullable = false)
  private Long regionId;

  @Column(name = "name", nullable = false, length = 50)
  private String name;

  @Column(name = "full_name", nullable = false, length = 100)
  private String fullName;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "level", nullable = false, length = 10)
  private RegionLevel level;

  @Column(name = "parent_region_id", nullable = true)
  private Long parentRegionId;
}
