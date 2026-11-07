package com.hobbytree.hobbytree.domain.profileimage.entity;

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

// userId is a plain column, not a @ManyToOne - the same ec2-profile lazy-loading avoidance as
// UserLocation/LocationConsent. One row per issued presigned URL; API-06 looks up the ISSUED
// row for (userId, objectKey) to confirm a key was actually handed to the linking user.
@Entity
@Table(name = "profile_image_uploads")
@Check(
    constraints =
        "(content_type IN ('image/jpeg','image/png','image/webp')) AND (reported_file_size BETWEEN 1 AND 5242880) AND (status IN ('ISSUED','LINKED'))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProfileImageUpload {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "profile_image_upload_id", nullable = false)
  private Long profileImageUploadId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "object_key", nullable = false, unique = true, length = 255)
  private String objectKey;

  @Column(name = "content_type", nullable = false, length = 20)
  private String contentType;

  @Column(name = "reported_file_size", nullable = false)
  private long reportedFileSize;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @ColumnDefault("'ISSUED'")
  @Column(name = "status", nullable = false, length = 10)
  private ProfileImageUploadStatus status;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  private ProfileImageUpload(
      Long userId, String objectKey, String contentType, long reportedFileSize, Instant expiresAt) {
    this.userId = userId;
    this.objectKey = objectKey;
    this.contentType = contentType;
    this.reportedFileSize = reportedFileSize;
    this.status = ProfileImageUploadStatus.ISSUED;
    this.expiresAt = expiresAt;
  }

  public static ProfileImageUpload issue(
      Long userId, String objectKey, String contentType, long reportedFileSize, Instant expiresAt) {
    return new ProfileImageUpload(userId, objectKey, contentType, reportedFileSize, expiresAt);
  }

  public void markLinked() {
    this.status = ProfileImageUploadStatus.LINKED;
  }
}
