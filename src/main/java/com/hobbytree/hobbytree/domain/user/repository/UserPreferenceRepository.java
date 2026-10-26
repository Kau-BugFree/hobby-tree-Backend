package com.hobbytree.hobbytree.domain.user.repository;

import com.hobbytree.hobbytree.domain.user.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {}
