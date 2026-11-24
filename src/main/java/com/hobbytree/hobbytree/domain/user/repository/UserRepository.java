package com.hobbytree.hobbytree.domain.user.repository;

import com.hobbytree.hobbytree.domain.user.entity.AccountStatus;
import com.hobbytree.hobbytree.domain.user.entity.AuthProvider;
import com.hobbytree.hobbytree.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
  Optional<User> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);

  // Issue #200: nickname uniqueness check that excludes the caller's own row, so re-submitting
  // the same nickname you already have is never treated as a conflict.
  boolean existsByNicknameAndUserIdNot(String nickname, Long userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.userId = :userId")
  Optional<User> findForLogin(@Param("userId") Long userId);

  // Issue #276: an unlocked batch scan for AccountAnonymizationScheduler - matches
  // idx_users_withdrawn_restorable_until exactly. Each candidate is re-checked and locked
  // individually in UserService.anonymizeIfStillWithdrawn before being mutated.
  @Query(
      "select u.userId from User u where u.accountStatus = :status and u.restorableUntil <= :cutoff")
  List<Long> findIdsByAccountStatusAndRestorableUntilAtMost(
      @Param("status") AccountStatus status, @Param("cutoff") Instant cutoff);
}
