package com.hobbytree.hobbytree.domain.sponsoredcontent.entity;

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

@Entity
@Table(name = "sponsored_contents")
@Check(
    constraints =
        "(content_type IN ('PRODUCT','CLASS','ARTICLE')) AND (status IN ('ACTIVE','INACTIVE')) AND (external_url ~ '^https://[^/]') AND (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SponsoredContent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "sponsored_content_id", nullable = false)
  private Long sponsoredContentId;

  @Column(name = "title", nullable = false, length = 160)
  private String title;

  @Column(name = "description", nullable = true, length = 500)
  private String description;

  @Column(name = "sponsor_name", nullable = false, length = 160)
  private String sponsorName;

  @Column(name = "thumbnail_url", nullable = true, columnDefinition = "text")
  private String thumbnailUrl;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "content_type", nullable = false, length = 20)
  private SponsoredContentType contentType;

  @Column(name = "disclosure_label", nullable = false, length = 30)
  private String disclosureLabel;

  // Server-curated allowed HTTPS address only; never derived from user input.
  @Column(name = "external_url", nullable = false, columnDefinition = "text")
  private String externalUrl;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @ColumnDefault("'ACTIVE'")
  @Column(name = "status", nullable = false, length = 20)
  private SponsoredContentStatus status = SponsoredContentStatus.ACTIVE;

  @Column(name = "starts_at", nullable = true)
  private Instant startsAt;

  @Column(name = "ends_at", nullable = true)
  private Instant endsAt;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
