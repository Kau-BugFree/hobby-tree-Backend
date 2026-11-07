package com.hobbytree.hobbytree.domain.idempotency.entity;

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

// userId is a plain column, not a @ManyToOne - same ec2-profile lazy-loading reason as
// UserLocation/LocationConsent. One row per (user, endpoint, key); IdempotencyKeyService is
// the only thing that ever reads/writes this table.
@Entity
@Table(name = "idempotency_keys")
@Check(constraints = "(status IN ('PENDING','COMPLETED'))")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "idempotency_key_id", nullable = false)
  private Long idempotencyKeyId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "endpoint", nullable = false, length = 80)
  private String endpoint;

  @Column(name = "idempotency_key", nullable = false, length = 100)
  private String idempotencyKey;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @ColumnDefault("'PENDING'")
  @Column(name = "status", nullable = false, length = 10)
  private IdempotencyKeyStatus status = IdempotencyKeyStatus.PENDING;

  @Column(name = "response_status", nullable = true)
  private Integer responseStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_body", nullable = true, columnDefinition = "jsonb")
  private String responseBody;

  @ColumnDefault("now()")
  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  private IdempotencyKey(Long userId, String endpoint, String idempotencyKey, String requestHash) {
    this.userId = userId;
    this.endpoint = endpoint;
    this.idempotencyKey = idempotencyKey;
    this.requestHash = requestHash;
  }

  public static IdempotencyKey issue(
      Long userId, String endpoint, String idempotencyKey, String requestHash) {
    return new IdempotencyKey(userId, endpoint, idempotencyKey, requestHash);
  }

  public void complete(int responseStatus, String responseBody) {
    this.status = IdempotencyKeyStatus.COMPLETED;
    this.responseStatus = responseStatus;
    this.responseBody = responseBody;
  }
}
