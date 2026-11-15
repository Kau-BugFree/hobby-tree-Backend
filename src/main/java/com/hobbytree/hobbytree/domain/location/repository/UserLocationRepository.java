package com.hobbytree.hobbytree.domain.location.repository;

import com.hobbytree.hobbytree.domain.location.entity.UserLocation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserLocationRepository extends JpaRepository<UserLocation, Long> {
  Optional<UserLocation> findByUserId(Long userId);
}
