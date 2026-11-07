package com.hobbytree.hobbytree.domain.idempotency.repository;

import com.hobbytree.hobbytree.domain.idempotency.entity.IdempotencyKey;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
  Optional<IdempotencyKey> findByUserIdAndEndpointAndIdempotencyKey(
      Long userId, String endpoint, String idempotencyKey);
}
