package com.hobbytree.hobbytree.domain.experience.entity;

import com.hobbytree.hobbytree.domain.activity.entity.Activity;
import com.hobbytree.hobbytree.domain.hobby.entity.Hobby;
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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "experiences")
@Check(
    constraints =
        "(experience_type IN ('CLASS','MEETUP','EXTERNAL')) AND (capacity > 0) AND (price_amount >= 0) AND (booking_mode IN ('EXTERNAL','INQUIRY','NONE')) AND (listing_status IN ('DRAFT','OPEN','CLOSED','CANCELLED')) AND (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Experience {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "experience_id", nullable = false)
  private Long experienceId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "experience_type", nullable = false, length = 20)
  private ExperienceType experienceType;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "linked_activity_id", nullable = true)
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private Activity linkedActivity;

  @Column(name = "title", nullable = false, length = 160)
  private String title;

  @Column(name = "description", nullable = true, columnDefinition = "text")
  private String description;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "hobby_id", nullable = true)
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private Hobby hobby;

  @Column(name = "region_sido", nullable = true, length = 50)
  private String regionSido;

  @Column(name = "region_sigungu", nullable = true, length = 50)
  private String regionSigungu;

  @Column(name = "venue_name", nullable = true, length = 160)
  private String venueName;

  @Column(name = "venue_address", nullable = true, columnDefinition = "text")
  private String venueAddress;

  @Column(name = "provider_name", nullable = true, length = 160)
  private String providerName;

  @Column(name = "partner_label", nullable = true, length = 30)
  private String partnerLabel;

  @Column(name = "starts_at", nullable = true)
  private Instant startsAt;

  @Column(name = "ends_at", nullable = true)
  private Instant endsAt;

  @Column(name = "capacity", nullable = true)
  private Integer capacity;

  @Column(name = "price_amount", nullable = true)
  private Integer priceAmount;

  @Column(name = "external_url", nullable = true, columnDefinition = "text")
  private String externalUrl;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @ColumnDefault("'EXTERNAL'")
  @Column(name = "booking_mode", nullable = false, length = 20)
  private BookingMode bookingMode = BookingMode.EXTERNAL;

  @Column(name = "thumbnail_image_key", nullable = true, columnDefinition = "text")
  private String thumbnailImageKey;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @ColumnDefault("'DRAFT'")
  @Column(name = "listing_status", nullable = false, length = 20)
  private ListingStatus listingStatus = ListingStatus.DRAFT;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @ColumnDefault("now()")
  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
