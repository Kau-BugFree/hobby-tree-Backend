package com.hobbytree.hobbytree.domain.user.repository;

import com.hobbytree.hobbytree.domain.user.entity.AuthProvider;
import com.hobbytree.hobbytree.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
  Optional<User> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.userId = :userId")
  Optional<User> findForLogin(@Param("userId") Long userId);
}
