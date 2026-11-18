package com.hobbytree.hobbytree.domain.user.repository;

import com.hobbytree.hobbytree.domain.user.entity.UserPreference;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {
  /** Tests profile mode without adding a preference entity to the request's persistence context. */
  boolean existsByUser_UserIdAndQuestionnaireIsNotNull(Long userId);

  Optional<UserPreference> findByUser_UserId(Long userId);
}
